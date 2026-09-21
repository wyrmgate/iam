package io.wyrmgate.iam.integration.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery.Freshness;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery.Status;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.ProviderObservation;
import io.wyrmgate.iam.integration.application.ConnectorWorkRepository.WorkCompletion;
import io.wyrmgate.iam.integration.application.WorkerRegistrationRepository.WorkerPermission;
import io.wyrmgate.iam.integration.domain.LeasedConnectorWork;
import io.wyrmgate.iam.integration.domain.WorkerCapability;
import io.wyrmgate.iam.integration.domain.WorkerRegistration;
import io.wyrmgate.iam.integration.domain.WorkerSession;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ConnectorWorkerProtocolService {

    private final WorkerRegistrationRepository workers;
    private final ConnectorWorkRepository work;
    private final DesiredAccessStateQuery desiredState;
    private final TransactionExecutor transactions;
    private final ConnectorWorkerProtocolProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public ConnectorWorkerProtocolService(
            WorkerRegistrationRepository workers,
            ConnectorWorkRepository work,
            DesiredAccessStateQuery desiredState,
            TransactionExecutor transactions,
            ConnectorWorkerProtocolProperties properties,
            ObjectMapper json) {
        this(workers, work, desiredState, transactions, properties, json, Clock.systemUTC());
    }

    ConnectorWorkerProtocolService(
            WorkerRegistrationRepository workers,
            ConnectorWorkRepository work,
            DesiredAccessStateQuery desiredState,
            TransactionExecutor transactions,
            ConnectorWorkerProtocolProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.work = Objects.requireNonNull(work, "work");
        this.desiredState = Objects.requireNonNull(desiredState, "desiredState");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkerSession establishSession(
            WorkerRegistration worker,
            String workerInstanceId,
            List<Integer> supportedProtocolMajors,
            List<RuntimeAdvertisement> advertised) {
        Instant now = clock.instant();
        if (!supportedProtocolMajors.contains(1) || !worker.supportsProtocol(1)) {
            throw new WorkerProtocolException("protocol_incompatible", "worker and server have no common protocol major");
        }
        List<WorkerSession.NegotiatedRuntime> negotiated = negotiate(
                workers.findPermissions(worker.id()), advertised);
        if (negotiated.isEmpty()) {
            throw new WorkerProtocolException("protocol_incompatible", "worker has no authorized compatible runtime/schema capability");
        }
        return transactions.required(() -> workers.insertSession(
                worker, requiredText(workerInstanceId, "workerInstanceId"), 1,
                now, now.plus(properties.effectiveSessionLifetime()), negotiated));
    }

    public WorkerSession requireSession(WorkerRegistration worker, UUID sessionId) {
        return workers.findActiveSession(sessionId, worker.id(), clock.instant())
                .orElseThrow(() -> new WorkerProtocolException("session_invalid", "session is invalid or expired"));
    }

    public List<LeasedConnectorWork> claim(
            WorkerRegistration worker, UUID sessionId, int maxItems, int waitSeconds) {
        if (maxItems < 1 || maxItems > properties.effectiveMaxClaimSize()) {
            throw new WorkerProtocolException("invalid_claim_limit", "maxItems exceeds server limits");
        }
        if (waitSeconds < 0 || waitSeconds > properties.effectiveMaxLongPollSeconds()) {
            throw new WorkerProtocolException("invalid_long_poll", "waitSeconds exceeds server limits");
        }
        Instant deadline = clock.instant().plusSeconds(waitSeconds);
        while (true) {
            List<LeasedConnectorWork> claimed = transactions.required(() -> claimOnce(worker, sessionId, maxItems));
            if (!claimed.isEmpty() || !clock.instant().isBefore(deadline)) return claimed;
            try {
                Thread.sleep(Math.min(250L, Math.max(1L, java.time.Duration.between(clock.instant(), deadline).toMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }
    }

    private List<LeasedConnectorWork> claimOnce(
            WorkerRegistration worker, UUID sessionId, int maxItems) {
        Instant now = clock.instant();
        WorkerSession session = workers.findActiveSession(sessionId, worker.id(), now)
                .orElseThrow(() -> new WorkerProtocolException("session_invalid", "session is invalid or expired"));
        List<LeasedConnectorWork> claimed = new ArrayList<>();

        for (var candidate : work.lockProvisioningCandidates(session, maxItems, now)) {
            DesiredAccessStateQuery.SubjectKind subjectKind;
            try {
                subjectKind = DesiredAccessStateQuery.SubjectKind.valueOf(candidate.subjectKind());
            } catch (IllegalArgumentException unsupportedSubjectKind) {
                work.supersedeProvisioning(
                        candidate.tenantId(), candidate.taskId(), candidate.desiredRevision(), now);
                continue;
            }
            Freshness freshness = desiredState.current(
                    new io.wyrmgate.iam.platform.tenant.TenantContext(candidate.tenantId()),
                    subjectKind,
                    candidate.subjectId());
            if (freshness.status() == Status.UNAVAILABLE) continue;
            if (freshness.status() == Status.ABSENT
                    || freshness.revision() != candidate.desiredRevision()) {
                work.supersedeProvisioning(
                        candidate.tenantId(), candidate.taskId(), candidate.desiredRevision(), now);
                continue;
            }
            claimed.add(work.leaseProvisioning(
                    session, candidate, now, properties.effectiveLeaseDuration()));
            if (claimed.size() == maxItems) return claimed;
        }

        int remaining = maxItems - claimed.size();
        if (remaining > 0) {
            for (var candidate : work.lockReconciliationCandidates(session, remaining, now)) {
                claimed.add(work.leaseReconciliation(
                        session, candidate, now, properties.effectiveLeaseDuration()));
            }
        }
        return List.copyOf(claimed);
    }

    public io.wyrmgate.iam.integration.domain.WorkerLease renew(
            WorkerRegistration worker, UUID sessionId, UUID workId,
            UUID leaseId, long leaseEpoch) {
        Instant now = clock.instant();
        return transactions.required(() -> work.renewLease(
                requireSession(worker, sessionId), workId, leaseId, leaseEpoch,
                now, properties.effectiveLeaseDuration()));
    }

    public ConnectorWorkRepository.ObservationBatchResult appendObservations(
            WorkerRegistration worker,
            UUID sessionId,
            UUID workId,
            UUID leaseId,
            long leaseEpoch,
            UUID batchId,
            int sequence,
            List<ProviderObservation> observations) {
        if (observations.isEmpty() || observations.size() > properties.effectiveMaxObservationBatchSize()) {
            throw new WorkerProtocolException("invalid_observation_batch", "observation batch size is outside server limits");
        }
        for (ProviderObservation observation : observations) {
            ConnectorPayloadGuard.requireSecretFree(observation.observedState());
        }
        String fingerprint = fingerprint(Map.of(
                "leaseId", leaseId.toString(),
                "leaseEpoch", leaseEpoch,
                "batchId", batchId.toString(),
                "sequence", sequence,
                "observations", observations));
        Instant now = clock.instant();
        return transactions.required(() -> work.appendProviderObservations(
                requireSession(worker, sessionId), workId, leaseId, leaseEpoch,
                batchId, sequence, fingerprint, observations, now));
    }

    public ConnectorWorkRepository.CompletionResult complete(
            WorkerRegistration worker,
            UUID sessionId,
            UUID workId,
            UUID leaseId,
            long leaseEpoch,
            WorkCompletion completion) {
        ConnectorPayloadGuard.requireSecretFree(completion.metadata());
        String fingerprint = fingerprint(completion);
        Instant now = clock.instant();
        return transactions.required(() -> work.complete(
                requireSession(worker, sessionId), workId, leaseId, leaseEpoch,
                completion, fingerprint, now));
    }

    public Limits limits() {
        return new Limits(
                properties.effectiveMaxClaimSize(),
                properties.effectiveMaxLongPollSeconds(),
                properties.effectiveMaxObservationBatchSize(),
                (int) properties.effectiveLeaseDuration().toSeconds());
    }

    private List<WorkerSession.NegotiatedRuntime> negotiate(
            List<WorkerPermission> allowed,
            List<RuntimeAdvertisement> advertised) {
        List<WorkerSession.NegotiatedRuntime> result = new ArrayList<>();
        for (RuntimeAdvertisement runtime : advertised) {
            Set<WorkerCapability> capabilities = new LinkedHashSet<>();
            Map<String,Set<Integer>> contracts = new LinkedHashMap<>();
            for (WorkerPermission permission : allowed) {
                if (!permission.runtimeId().equals(runtime.runtimeId())
                        || !permission.runtimeVersion().equals(runtime.runtimeVersion())
                        || !runtime.capabilities().contains(permission.capability())) continue;
                boolean versionAdvertised = runtime.contracts().stream()
                        .filter(c -> c.contractId().equals(permission.contractId()))
                        .anyMatch(c -> c.versions().contains(permission.contractVersion()));
                if (!versionAdvertised) continue;
                capabilities.add(permission.capability());
                contracts.computeIfAbsent(permission.contractId(), ignored -> new LinkedHashSet<>())
                        .add(permission.contractVersion());
            }
            if (!capabilities.isEmpty() && !contracts.isEmpty()) {
                result.add(new WorkerSession.NegotiatedRuntime(
                        runtime.runtimeId(), runtime.runtimeVersion(),
                        List.copyOf(capabilities),
                        contracts.entrySet().stream()
                                .map(e -> new WorkerSession.ContractSupport(e.getKey(), List.copyOf(e.getValue())))
                                .toList()));
            }
        }
        return List.copyOf(result);
    }

    private String fingerprint(Object value) {
        try {
            byte[] bytes = json.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(value);
            return RequestFingerprint.sha256(bytes).value();
        } catch (JsonProcessingException invalid) {
            throw new WorkerProtocolException("invalid_payload", "payload cannot be normalized for idempotency");
        }
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) throw new WorkerProtocolException("invalid_request", name + " must not be blank");
        return value;
    }

    public record RuntimeAdvertisement(
            String runtimeId,
            String runtimeVersion,
            List<WorkerCapability> capabilities,
            List<ContractAdvertisement> contracts) {
        public RuntimeAdvertisement {
            requiredText(runtimeId, "runtimeId");
            requiredText(runtimeVersion, "runtimeVersion");
            capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            contracts = List.copyOf(Objects.requireNonNull(contracts, "contracts"));
        }
    }

    public record ContractAdvertisement(String contractId, List<Integer> versions) {
        public ContractAdvertisement {
            requiredText(contractId, "contractId");
            versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        }
    }

    public record Limits(
            int maxClaimSize,
            int maxLongPollSeconds,
            int maxObservationBatchSize,
            int leaseDurationSeconds) {}
}
