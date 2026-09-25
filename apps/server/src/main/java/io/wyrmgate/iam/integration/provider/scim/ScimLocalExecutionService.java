package io.wyrmgate.iam.integration.provider.scim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery.Freshness;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery.Status;
import io.wyrmgate.iam.integration.application.ConnectorExecutionRepository;
import io.wyrmgate.iam.integration.application.ConnectorPayloadGuard;
import io.wyrmgate.iam.integration.application.WorkerProtocolException;
import io.wyrmgate.iam.integration.application.ConnectorExecutionRepository.ExecutionConfiguration;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProviderObservation;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProvisioningCandidate;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ReconciliationCandidate;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.WorkCompletion;
import io.wyrmgate.iam.integration.domain.LeasedConnectorWork;
import io.wyrmgate.iam.integration.domain.ReconciliationCompleteness;
import io.wyrmgate.iam.integration.domain.WorkerCapability;
import io.wyrmgate.iam.integration.domain.WorkerSession;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScimLocalExecutionService {

    private final ConnectorExecutionRepository execution;
    private final ConnectorWorkRepository work;
    private final DesiredAccessStateQuery desiredState;
    private final TransactionExecutor transactions;
    private final ScimPrincipalProviderAdapter scim;
    private final ScimGroupProviderAdapter groups;
    private final ScimLocalExecutionProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public ScimLocalExecutionService(
            ConnectorExecutionRepository execution,
            ConnectorWorkRepository work,
            DesiredAccessStateQuery desiredState,
            TransactionExecutor transactions,
            ScimPrincipalProviderAdapter scim,
            ScimGroupProviderAdapter groups,
            ScimLocalExecutionProperties properties,
            ObjectMapper json) {
        this(execution, work, desiredState, transactions, scim, groups, properties, json, Clock.systemUTC());
    }

    ScimLocalExecutionService(
            ConnectorExecutionRepository execution,
            ConnectorWorkRepository work,
            DesiredAccessStateQuery desiredState,
            TransactionExecutor transactions,
            ScimPrincipalProviderAdapter scim,
            ScimGroupProviderAdapter groups,
            ScimLocalExecutionProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.work = Objects.requireNonNull(work, "work");
        this.desiredState = Objects.requireNonNull(desiredState, "desiredState");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.scim = Objects.requireNonNull(scim, "scim");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int executeAvailable() {
        int remaining = properties.effectiveBatchSize();
        int provisioningLimit = remaining;
        List<ClaimedProvisioning> provisioning = transactions.required(
                () -> claimProvisioning(provisioningLimit));
        for (ClaimedProvisioning claimed : provisioning) {
            executeProvisioning(claimed);
        }
        remaining -= provisioning.size();
        if (remaining <= 0) return provisioning.size();

        int reconciliationLimit = remaining;
        List<LeasedConnectorWork> reconciliation = transactions.required(
                () -> claimReconciliation(reconciliationLimit));
        for (LeasedConnectorWork claimed : reconciliation) {
            executeReconciliation(claimed);
        }
        return provisioning.size() + reconciliation.size();
    }

    private List<ClaimedProvisioning> claimProvisioning(int limit) {
        Instant now = clock.instant();
        List<ProvisioningCandidate> candidates = new ArrayList<>();
        candidates.addAll(execution.lockLocalProvisioningCandidates(
                ScimPrincipalProviderAdapter.RUNTIME_ID,
                ScimPrincipalProviderAdapter.RUNTIME_VERSION,
                ScimPrincipalProviderAdapter.CONTRACT_ID,
                ScimPrincipalProviderAdapter.CONTRACT_VERSION,
                limit,
                now));
        int remaining = limit - candidates.size();
        if (remaining > 0) {
            candidates.addAll(execution.lockLocalProvisioningCandidates(
                    ScimGroupProviderAdapter.RUNTIME_ID,
                    ScimGroupProviderAdapter.RUNTIME_VERSION,
                    ScimGroupProviderAdapter.CONTRACT_ID,
                    ScimGroupProviderAdapter.CONTRACT_VERSION,
                    remaining,
                    now));
        }

        List<ClaimedProvisioning> claimed = new ArrayList<>();
        for (ProvisioningCandidate candidate : candidates) {
            DesiredAccessStateQuery.SubjectKind subjectKind;
            try {
                subjectKind = DesiredAccessStateQuery.SubjectKind.valueOf(candidate.subjectKind());
            } catch (IllegalArgumentException unsupported) {
                work.supersedeProvisioning(
                        candidate.tenantId(), candidate.taskId(), candidate.desiredRevision(), now);
                continue;
            }
            Freshness freshness = desiredState.current(
                    new TenantContext(candidate.tenantId()), subjectKind, candidate.subjectId());
            if (freshness.status() == Status.UNAVAILABLE) continue;
            if (freshness.status() == Status.ABSENT
                    || freshness.revision() != candidate.desiredRevision()) {
                work.supersedeProvisioning(
                        candidate.tenantId(), candidate.taskId(), candidate.desiredRevision(), now);
                continue;
            }
            WorkerSession owner = localOwner(new TenantContext(candidate.tenantId()));
            claimed.add(new ClaimedProvisioning(
                    candidate,
                    work.leaseProvisioning(
                            owner, candidate, now, properties.effectiveLeaseDuration())));
        }
        return List.copyOf(claimed);
    }

    private List<LeasedConnectorWork> claimReconciliation(int limit) {
        Instant now = clock.instant();
        List<ReconciliationCandidate> candidates = new ArrayList<>();
        candidates.addAll(execution.lockLocalReconciliationCandidates(
                ScimPrincipalProviderAdapter.RUNTIME_ID,
                ScimPrincipalProviderAdapter.RUNTIME_VERSION,
                ScimPrincipalProviderAdapter.CONTRACT_ID,
                ScimPrincipalProviderAdapter.CONTRACT_VERSION,
                limit,
                now));
        int remaining = limit - candidates.size();
        if (remaining > 0) {
            candidates.addAll(execution.lockLocalReconciliationCandidates(
                    ScimGroupProviderAdapter.RUNTIME_ID,
                    ScimGroupProviderAdapter.RUNTIME_VERSION,
                    ScimGroupProviderAdapter.CONTRACT_ID,
                    ScimGroupProviderAdapter.CONTRACT_VERSION,
                    remaining,
                    now));
        }
        List<LeasedConnectorWork> claimed = new ArrayList<>();
        for (ReconciliationCandidate candidate : candidates) {
            WorkerSession owner = localOwner(new TenantContext(candidate.tenantId()));
            claimed.add(work.leaseReconciliation(
                    owner, candidate, now, properties.effectiveLeaseDuration()));
        }
        return List.copyOf(claimed);
    }

    private void executeProvisioning(ClaimedProvisioning claimed) {
        ProvisioningCandidate candidate = claimed.candidate();
        LeasedConnectorWork leased = claimed.leased();
        WorkerSession owner = localOwner(leased.tenant());

        Freshness freshness = desiredState.current(
                leased.tenant(),
                DesiredAccessStateQuery.SubjectKind.valueOf(candidate.subjectKind()),
                candidate.subjectId());
        if (freshness.status() == Status.UNAVAILABLE) {
            complete(owner, leased, new WorkCompletion(
                    "FAILED_RETRYABLE", "TRANSIENT", "desired_state_unavailable",
                    null, 30, null, null, Map.of(), null, null));
            return;
        }
        if (freshness.status() == Status.ABSENT
                || freshness.revision() != candidate.desiredRevision()) {
            complete(owner, leased, new WorkCompletion(
                    "SUPERSEDED", null, "desired_revision_stale",
                    null, null, null, null, Map.of(), null, null));
            return;
        }

        ExecutionConfiguration configuration = execution
                .findExecutionConfiguration(leased.tenant(), leased.connectorBindingId())
                .filter(value -> isCompatible(value, leased.contractId(), leased.contractVersion()))
                .orElse(null);
        if (configuration == null || configuration.secretReference() == null
                || configuration.secretReference().isBlank()) {
            complete(owner, leased, new WorkCompletion(
                    "FAILED_FINAL", "VALIDATION", "connector_configuration_unavailable",
                    null, null, null, null, Map.of(), null, null));
            return;
        }

        try {
            ConnectorPayloadGuard.requireSecretFree(candidate.payload());
            Map<String,Object> payload = candidate.payload();
            if (ScimPrincipalProviderAdapter.CONTRACT_ID.equals(leased.contractId())) {
                ScimPrincipalProviderAdapter.ProvisioningResult result =
                        switch (candidate.operationType()) {
                            case "UPSERT_PRINCIPAL" -> upsert(
                                    scimConfiguration(configuration.configuration()),
                                    configuration.secretReference(),
                                    payload,
                                    candidate.idempotencyKey());
                            case "DISABLE_PRINCIPAL", "DEACTIVATE_PRINCIPAL" ->
                                    scim.disablePrincipal(
                                            scimConfiguration(configuration.configuration()),
                                            configuration.secretReference(),
                                            requiredText(payload, "providerStableId"),
                                            text(payload, "providerVersion"),
                                            candidate.idempotencyKey());
                            default -> throw new UnsupportedOperationException(
                                    "unsupported SCIM principal provisioning operation");
                        };
                complete(owner, leased, new WorkCompletion(
                        "SUCCEEDED", null, null, result.providerRequestId(), null,
                        result.providerStableId(), result.providerVersion(),
                        Map.of(), null, null));
            } else if (ScimGroupProviderAdapter.CONTRACT_ID.equals(leased.contractId())) {
                if (!"DESIRED_GRANT".equals(candidate.subjectKind())) {
                    throw new UnsupportedOperationException(
                            "SCIM Group membership work requires DESIRED_GRANT");
                }
                ScimGroupProviderAdapter.Configuration groupConfiguration =
                        groupConfiguration(configuration.configuration());
                ScimGroupProviderAdapter.ProvisioningResult result =
                        switch (candidate.operationType()) {
                            case "ADD_GRANT" -> groups.addGrant(
                                    groupConfiguration,
                                    configuration.secretReference(),
                                    requiredAnyText(
                                            payload,
                                            "providerEntitlementId",
                                            "providerGroupId"),
                                    firstText(
                                            payload,
                                            "providerEntitlementVersion",
                                            "providerGroupVersion"),
                                    requiredText(payload, "providerPrincipalId"),
                                    candidate.idempotencyKey());
                            case "REMOVE_GRANT" -> groups.removeGrant(
                                    groupConfiguration,
                                    configuration.secretReference(),
                                    requiredAnyText(
                                            payload,
                                            "providerEntitlementId",
                                            "providerGroupId"),
                                    firstText(
                                            payload,
                                            "providerEntitlementVersion",
                                            "providerGroupVersion"),
                                    requiredText(payload, "providerPrincipalId"),
                                    candidate.idempotencyKey());
                            default -> throw new UnsupportedOperationException(
                                    "unsupported SCIM grant provisioning operation");
                        };
                complete(owner, leased, new WorkCompletion(
                        "SUCCEEDED", null, null, result.providerRequestId(), null,
                        result.providerStableId(), result.providerVersion(),
                        Map.of(), null, null));
            } else {
                throw new UnsupportedOperationException("unsupported SCIM contract");
            }
        } catch (ScimProviderException provider) {
            complete(owner, leased, providerCompletion(provider, null, null));
        } catch (WorkerProtocolException secretViolation) {
            complete(owner, leased, new WorkCompletion(
                    "FAILED_FINAL", "VALIDATION", "secret_material_forbidden",
                    null, null, null, null, Map.of(), null, null));
        } catch (IllegalArgumentException | UnsupportedOperationException invalid) {
            complete(owner, leased, new WorkCompletion(
                    "FAILED_FINAL", "VALIDATION", "invalid_scim_work",
                    null, null, null, null, Map.of(), null, null));
        }
    }

    private void executeReconciliation(LeasedConnectorWork leased) {
        WorkerSession owner = localOwner(leased.tenant());
        ExecutionConfiguration configuration = execution
                .findExecutionConfiguration(leased.tenant(), leased.connectorBindingId())
                .filter(value -> isCompatible(value, leased.contractId(), leased.contractVersion()))
                .orElse(null);
        if (configuration == null || configuration.secretReference() == null
                || configuration.secretReference().isBlank()) {
            complete(owner, leased, new WorkCompletion(
                    "FAILED_FINAL", "VALIDATION", "connector_configuration_unavailable",
                    null, null, null, null, Map.of(),
                    ReconciliationCompleteness.UNKNOWN, leased.checkpoint()));
            return;
        }

        AtomicInteger sequence = new AtomicInteger(transactions.required(
                () -> execution.nextObservationSequence(leased.tenant(), leased.workId())));
        try {
            ReconciliationCompleteness coverage;
            String nextCheckpoint;
            String objectClass = text(leased.payload(), "objectClass");
            if (ScimPrincipalProviderAdapter.CONTRACT_ID.equals(leased.contractId())) {
                if (!"PRINCIPAL".equals(objectClass)) {
                    throw new IllegalArgumentException(
                            "principal contract requires PRINCIPAL reconciliation scope");
                }
                var result = scim.discoverPrincipals(
                        scimConfiguration(configuration.configuration()),
                        configuration.secretReference(),
                        leased.checkpoint(),
                        observations -> appendObservationBatch(
                                owner, leased, sequence.getAndIncrement(), observations));
                coverage = result.coverage();
                nextCheckpoint = result.nextCheckpoint();
            } else if (ScimGroupProviderAdapter.CONTRACT_ID.equals(leased.contractId())) {
                ScimGroupProviderAdapter.Configuration groupConfiguration =
                        groupConfiguration(configuration.configuration());
                ScimGroupProviderAdapter.DiscoveryResult result;
                if ("ENTITLEMENT".equals(objectClass)) {
                    result = groups.discoverEntitlements(
                            groupConfiguration,
                            configuration.secretReference(),
                            leased.checkpoint(),
                            observations -> appendObservationBatch(
                                    owner, leased, sequence.getAndIncrement(), observations));
                } else if ("GRANT".equals(objectClass)) {
                    result = groups.discoverGrants(
                            groupConfiguration,
                            configuration.secretReference(),
                            leased.checkpoint(),
                            observations -> appendObservationBatch(
                                    owner, leased, sequence.getAndIncrement(), observations));
                } else {
                    throw new IllegalArgumentException(
                            "group contract requires ENTITLEMENT or GRANT reconciliation scope");
                }
                coverage = result.coverage();
                nextCheckpoint = result.nextCheckpoint();
            } else {
                throw new IllegalArgumentException("unsupported SCIM contract");
            }

            complete(owner, leased, new WorkCompletion(
                    "SUCCEEDED", null, null, null, null, null, null, Map.of(),
                    coverage, nextCheckpoint));
        } catch (ScimProviderException provider) {
            complete(owner, leased, providerCompletion(
                    provider, ReconciliationCompleteness.UNKNOWN, leased.checkpoint()));
        } catch (IllegalArgumentException invalid) {
            complete(owner, leased, new WorkCompletion(
                    "FAILED_FINAL", "VALIDATION", "invalid_scim_configuration",
                    null, null, null, null, Map.of(),
                    ReconciliationCompleteness.UNKNOWN, leased.checkpoint()));
        }
    }

    private ScimPrincipalProviderAdapter.ProvisioningResult upsert(
            ScimPrincipalProviderAdapter.Configuration configuration,
            String secretReference,
            Map<String,Object> payload,
            String idempotencyKey) {
        String providerStableId = text(payload, "providerStableId");
        var write = new ScimPrincipalProviderAdapter.PrincipalWrite(
                text(payload, "userName"),
                text(payload, "displayName"),
                text(payload, "externalId"),
                booleanValue(payload, "active"));
        if (providerStableId == null) {
            return scim.createPrincipal(
                    configuration, secretReference, write, idempotencyKey);
        }
        return scim.updatePrincipal(
                configuration,
                secretReference,
                providerStableId,
                text(payload, "providerVersion"),
                write,
                idempotencyKey);
    }

    private void appendObservationBatch(
            WorkerSession owner,
            LeasedConnectorWork leased,
            int sequence,
            List<ProviderObservation> observations) {
        UUID batchId = UUID.nameUUIDFromBytes(
                (leased.workId() + ":" + sequence).getBytes(StandardCharsets.UTF_8));
        String requestFingerprint = fingerprint(Map.of(
                "leaseId", leased.lease().leaseId().toString(),
                "leaseEpoch", leased.lease().leaseEpoch(),
                "batchId", batchId.toString(),
                "sequence", sequence,
                "observations", observations));
        transactions.required(() -> work.appendProviderObservations(
                owner,
                leased.workId(),
                leased.lease().leaseId(),
                leased.lease().leaseEpoch(),
                batchId,
                sequence,
                requestFingerprint,
                observations,
                clock.instant()));
    }

    private void complete(
            WorkerSession owner,
            LeasedConnectorWork leased,
            WorkCompletion completion) {
        String completionFingerprint = fingerprint(completion);
        transactions.required(() -> work.complete(
                owner,
                leased.workId(),
                leased.lease().leaseId(),
                leased.lease().leaseEpoch(),
                completion,
                completionFingerprint,
                clock.instant()));
    }

    private WorkCompletion providerCompletion(
            ScimProviderException provider,
            ReconciliationCompleteness coverage,
            String checkpoint) {
        boolean retryable = provider.category() == ScimProviderException.FailureCategory.TRANSIENT
                || provider.category() == ScimProviderException.FailureCategory.RATE_LIMITED;
        return new WorkCompletion(
                retryable ? "FAILED_RETRYABLE" : "FAILED_FINAL",
                provider.category().name(),
                provider.providerErrorCode(),
                provider.providerRequestId(),
                provider.retryAfterSeconds(),
                null,
                null,
                Map.of(),
                coverage,
                checkpoint);
    }

    private boolean isCompatible(
            ExecutionConfiguration configuration,
            String contractId,
            int contractVersion) {
        return "SCIM_2".equals(configuration.connectorType())
                && ScimPrincipalProviderAdapter.RUNTIME_ID.equals(configuration.runtimeId())
                && ScimPrincipalProviderAdapter.RUNTIME_VERSION.equals(configuration.runtimeVersion())
                && contractId.equals(configuration.contractId())
                && contractVersion == configuration.contractVersion()
                && (
                    (ScimPrincipalProviderAdapter.CONTRACT_ID.equals(contractId)
                        && ScimPrincipalProviderAdapter.CONTRACT_VERSION == contractVersion)
                    || (ScimGroupProviderAdapter.CONTRACT_ID.equals(contractId)
                        && ScimGroupProviderAdapter.CONTRACT_VERSION == contractVersion)
                );
    }

    private static ScimPrincipalProviderAdapter.Configuration scimConfiguration(
            Map<String,Object> configuration) {
        String baseUri = requiredText(configuration, "baseUri");
        int pageSize = integer(configuration, "pageSize", 100);
        int maxPages = integer(configuration, "maxPagesPerExecution", 1000);
        Duration requestTimeout = duration(
                configuration, "requestTimeout",
                Duration.ofSeconds(integer(configuration, "requestTimeoutSeconds", 30)));
        return new ScimPrincipalProviderAdapter.Configuration(
                URI.create(baseUri),
                pageSize,
                maxPages,
                requestTimeout,
                text(configuration, "idempotencyHeader"));
    }

    private static ScimGroupProviderAdapter.Configuration groupConfiguration(
            Map<String,Object> configuration) {
        String baseUri = requiredText(configuration, "baseUri");
        int pageSize = integer(configuration, "pageSize", 100);
        int maxPages = integer(configuration, "maxPagesPerExecution", 1000);
        Duration requestTimeout = duration(
                configuration, "requestTimeout",
                Duration.ofSeconds(integer(configuration, "requestTimeoutSeconds", 30)));
        return new ScimGroupProviderAdapter.Configuration(
                URI.create(baseUri),
                pageSize,
                maxPages,
                requestTimeout,
                text(configuration, "idempotencyHeader"));
    }

    private WorkerSession localOwner(TenantContext tenant) {
        UUID ownerId = UUID.nameUUIDFromBytes(
                ("wyrmgate:scim-local:" + tenant.tenantId())
                        .getBytes(StandardCharsets.UTF_8));
        return new WorkerSession(
                ownerId,
                ownerId,
                tenant,
                "scim-local",
                1,
                Instant.MAX,
                List.of(new WorkerSession.NegotiatedRuntime(
                        ScimPrincipalProviderAdapter.RUNTIME_ID,
                        ScimPrincipalProviderAdapter.RUNTIME_VERSION,
                        List.of(WorkerCapability.PROVISION, WorkerCapability.RECONCILE),
                        List.of(
                                new WorkerSession.ContractSupport(
                                        ScimPrincipalProviderAdapter.CONTRACT_ID,
                                        List.of(ScimPrincipalProviderAdapter.CONTRACT_VERSION)),
                                new WorkerSession.ContractSupport(
                                        ScimGroupProviderAdapter.CONTRACT_ID,
                                        List.of(ScimGroupProviderAdapter.CONTRACT_VERSION))))));
    }

    private String fingerprint(Object value) {
        try {
            byte[] bytes = json.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(value);
            return RequestFingerprint.sha256(bytes).value();
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("connector work cannot be normalized", invalid);
        }
    }

    private static String requiredAnyText(
            Map<String,Object> map,
            String primaryKey,
            String fallbackKey) {
        String value = firstText(map, primaryKey, fallbackKey);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    primaryKey + " must not be blank");
        }
        return value;
    }

    private static String firstText(
            Map<String,Object> map,
            String primaryKey,
            String fallbackKey) {
        String primary = text(map, primaryKey);
        return primary != null ? primary : text(map, fallbackKey);
    }

    private static String requiredText(Map<String,Object> map, String key) {
        String value = text(map, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        return value;
    }

    private static String text(Map<String,Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(Map<String,Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static Duration duration(
            Map<String,Object> map,
            String key,
            Duration defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        try {
            return Duration.parse(String.valueOf(value));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be an ISO-8601 duration");
        }
    }

    private static Boolean booleanValue(Map<String,Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Boolean booleanValue) return booleanValue;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return Boolean.FALSE;
        throw new IllegalArgumentException(key + " must be boolean");
    }

    private record ClaimedProvisioning(
            ProvisioningCandidate candidate,
            LeasedConnectorWork leased) {}
}
