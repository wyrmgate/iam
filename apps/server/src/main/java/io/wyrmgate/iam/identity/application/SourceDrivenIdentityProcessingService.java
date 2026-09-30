package io.wyrmgate.iam.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import io.wyrmgate.iam.identity.domain.SourceCorrelationPolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Durable positive SourceRecord Joiner/Mover processing under ADR-0022.
 *
 * <p>Destructive absence and Leaver inference are intentionally outside this processor.</p>
 */
public final class SourceDrivenIdentityProcessingService {

    public static final String SOURCE_RECORD_OBSERVED = "identity.source-record-observed";
    public static final String IDENTITY_LINK_ACCEPTED = "identity.identity-link-accepted";

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 100;

    private final JdbcOutboxRepository outbox;
    private final SourceCorrelationRepository sources;
    private final CanonicalAttributeRepository attributes;
    private final SourceCorrelationService correlations;
    private final IdentityCommandService identities;
    private final CanonicalAttributeResolutionService resolution;
    private final SourceMappedValueExtractor extractor;
    private final TransactionExecutor transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public SourceDrivenIdentityProcessingService(
            JdbcOutboxRepository outbox,
            SourceCorrelationRepository sources,
            CanonicalAttributeRepository attributes,
            SourceCorrelationService correlations,
            IdentityCommandService identities,
            CanonicalAttributeResolutionService resolution,
            SourceMappedValueExtractor extractor,
            TransactionExecutor transactions,
            ObjectMapper json) {
        this(
                outbox,
                sources,
                attributes,
                correlations,
                identities,
                resolution,
                extractor,
                transactions,
                json,
                Clock.systemUTC());
    }

    SourceDrivenIdentityProcessingService(
            JdbcOutboxRepository outbox,
            SourceCorrelationRepository sources,
            CanonicalAttributeRepository attributes,
            SourceCorrelationService correlations,
            IdentityCommandService identities,
            CanonicalAttributeResolutionService resolution,
            SourceMappedValueExtractor extractor,
            TransactionExecutor transactions,
            ObjectMapper json,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.attributes = Objects.requireNonNull(attributes, "attributes");
        this.correlations = Objects.requireNonNull(correlations, "correlations");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BatchResult processAvailable() {
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(SOURCE_RECORD_OBSERVED, IDENTITY_LINK_ACCEPTED),
                clock.instant(),
                CLAIM_LEASE,
                BATCH_SIZE);
        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                consume(item);
                outbox.markPublished(
                        item.tenant(), item.event().eventId(), clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(),
                        item.event().eventId(),
                        "source_identity_fact_invalid");
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "source_identity_processing_failed");
                failed++;
            }
        }
        return new BatchResult(claimed.size(), processed, failed);
    }

    private void consume(ClaimedOutboxEvent item) {
        var event = item.event();
        if (event.eventVersion() != 1) {
            throw new IllegalArgumentException("unsupported source Identity fact version");
        }
        UUID correlationId = event.correlationId() == null
                ? event.eventId()
                : event.correlationId();
        JsonNode payload = payload(event.payloadJson());

        if (SOURCE_RECORD_OBSERVED.equals(event.eventType())) {
            UUID sourceRecordId = requiredUuid(payload, "sourceRecordId");
            SourceRecord current = sources.findSourceRecord(item.tenant(), sourceRecordId)
                    .orElseThrow(() -> new IllegalArgumentException("source record does not exist"));
            JsonNode observedAt = payload.get("observedAt");
            if (observedAt != null && observedAt.isTextual()) {
                Instant factObservedAt;
                try {
                    factObservedAt = Instant.parse(observedAt.textValue());
                } catch (RuntimeException invalid) {
                    throw new IllegalArgumentException("observedAt is invalid", invalid);
                }
                if (factObservedAt.isBefore(current.lastObservedAt())) {
                    return;
                }
            }

            UUID identityId = ensureCorrelation(
                    item.tenant(),
                    sourceRecordId,
                    correlationId,
                    event.eventId(),
                    clock.instant());
            if (identityId != null) {
                materializeCurrentMappingCandidates(
                        item.tenant(),
                        sourceRecordId,
                        correlationId,
                        event.eventId(),
                        clock.instant());
            }
            return;
        }

        if (IDENTITY_LINK_ACCEPTED.equals(event.eventType())) {
            UUID sourceRecordId = requiredUuid(payload, "sourceRecordId");
            UUID previousIdentityId = optionalUuid(payload, "previousIdentityId");
            materializeCurrentMappingCandidates(
                    item.tenant(),
                    sourceRecordId,
                    correlationId,
                    event.eventId(),
                    clock.instant());
            if (previousIdentityId != null) {
                reresolvePreviousIdentity(
                        item.tenant(),
                        sourceRecordId,
                        previousIdentityId,
                        correlationId,
                        event.eventId(),
                        clock.instant());
            }
            return;
        }

        throw new IllegalArgumentException("unsupported source Identity fact");
    }

    private UUID ensureCorrelation(
            TenantContext tenant,
            UUID sourceRecordId,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        return transactions.required(() -> {
            SourceRecord record = sources.findSourceRecordForUpdate(tenant, sourceRecordId)
                    .orElseThrow(() -> new IllegalArgumentException("source record does not exist"));
            var existing = sources.findActiveAcceptedLink(tenant, sourceRecordId);
            if (existing.isPresent()) {
                return existing.get().identityId();
            }

            SourceCorrelationPolicyVersion policy =
                    sources.findActiveCorrelationPolicy(tenant, record.sourceSystemId())
                            .orElse(null);
            if (policy == null) {
                return null;
            }

            CanonicalAttributeRepository.ActiveSourceMapping matchMapping =
                    currentPolicyMapping(tenant, record, policy);
            if (matchMapping == null) {
                return null;
            }

            String matchValue = extractor.nonBlankText(
                            record.observedAttributesJson(),
                            matchMapping.mapping().sourcePath())
                    .orElse(null);
            if (matchValue == null) {
                return null;
            }

            List<UUID> matches = attributes.findIdentityIdsByResolvedSingleStringValue(
                    tenant,
                    policy.matchAttributeDefinitionVersionId(),
                    matchValue,
                    2);
            if (matches.size() == 1) {
                return correlations.acceptCorrelation(
                                tenant,
                                sourceRecordId,
                                matches.getFirst(),
                                "automatic exact governed canonical match policy "
                                        + policy.id(),
                                now,
                                correlationId,
                                causationId)
                        .identityId();
            }
            if (matches.size() > 1 || !policy.createIdentityOnNoMatch()) {
                return null;
            }

            // Serialize the no-match creation window across the tenant, then re-evaluate.
            // This avoids duplicate source-created Identities for the same canonical match value
            // when multiple sources/records race before candidates are materialized.
            sources.lockTenantForCorrelation(tenant);
            existing = sources.findActiveAcceptedLink(tenant, sourceRecordId);
            if (existing.isPresent()) {
                return existing.get().identityId();
            }
            matches = attributes.findIdentityIdsByResolvedSingleStringValue(
                    tenant,
                    policy.matchAttributeDefinitionVersionId(),
                    matchValue,
                    2);
            if (matches.size() == 1) {
                return correlations.acceptCorrelation(
                                tenant,
                                sourceRecordId,
                                matches.getFirst(),
                                "automatic exact governed canonical match after creation fence policy "
                                        + policy.id(),
                                now,
                                correlationId,
                                causationId)
                        .identityId();
            }
            if (matches.size() > 1) {
                return null;
            }

            String displayName = extractor.nonBlankText(
                            record.observedAttributesJson(),
                            policy.displayNameSourcePath())
                    .orElse(null);
            if (displayName == null) {
                return null;
            }

            IdentityType type = policy.createdIdentityType();
            var created = identities.create(
                    tenant,
                    type,
                    profile(type),
                    IdentityLifecycleState.PENDING,
                    displayName,
                    now,
                    correlationId,
                    causationId);
            correlations.acceptCorrelation(
                    tenant,
                    sourceRecordId,
                    created.id(),
                    "policy-authorized source no-match creation policy " + policy.id(),
                    now,
                    correlationId,
                    causationId);

            // Publish the exact governed correlation key into canonical state before releasing
            // the tenant-scoped no-match fence. A racing source can then deterministically match
            // this Identity instead of creating a duplicate.
            resolution.recordCandidate(
                    tenant,
                    sourceRecordId,
                    matchMapping.canonicalKey(),
                    List.of(new CanonicalValue.StringValue(matchValue)),
                    record.lastObservedAt());
            resolution.resolve(
                    tenant,
                    created.id(),
                    matchMapping.canonicalKey(),
                    now,
                    correlationId,
                    causationId);
            return created.id();
        });
    }

    private CanonicalAttributeRepository.ActiveSourceMapping currentPolicyMapping(
            TenantContext tenant,
            SourceRecord record,
            SourceCorrelationPolicyVersion policy) {
        return attributes.findActiveMappingsForSource(tenant, record.sourceSystemId())
                .stream()
                .filter(mapping -> mapping.mapping().id().equals(policy.matchMappingVersionId()))
                .filter(mapping -> mapping.definitionVersion().id()
                        .equals(policy.matchAttributeDefinitionVersionId()))
                .findFirst()
                .orElse(null);
    }

    private void materializeCurrentMappingCandidates(
            TenantContext tenant,
            UUID sourceRecordId,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        transactions.required(() -> {
            SourceRecord record = sources.findSourceRecordForUpdate(tenant, sourceRecordId)
                    .orElseThrow(() -> new IllegalArgumentException("source record does not exist"));
            var link = sources.findActiveAcceptedLink(tenant, sourceRecordId);
            if (link.isEmpty()) {
                return null;
            }
            UUID identityId = link.get().identityId();

            for (CanonicalAttributeRepository.ActiveSourceMapping mapping :
                    attributes.findActiveMappingsForSource(tenant, record.sourceSystemId())) {
                var values = extractor.values(
                        record.observedAttributesJson(),
                        mapping.mapping().sourcePath(),
                        mapping.definitionVersion());
                if (values.isEmpty()) {
                    continue;
                }
                resolution.recordCandidate(
                        tenant,
                        sourceRecordId,
                        mapping.canonicalKey(),
                        values.get(),
                        record.lastObservedAt());
                resolution.resolve(
                        tenant,
                        identityId,
                        mapping.canonicalKey(),
                        now,
                        correlationId,
                        causationId);
            }
            return null;
        });
    }

    private void reresolvePreviousIdentity(
            TenantContext tenant,
            UUID sourceRecordId,
            UUID previousIdentityId,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        transactions.required(() -> {
            SourceRecord record = sources.findSourceRecordForUpdate(tenant, sourceRecordId)
                    .orElseThrow(() -> new IllegalArgumentException("source record does not exist"));
            for (CanonicalAttributeRepository.ActiveSourceMapping mapping :
                    attributes.findActiveMappingsForSource(tenant, record.sourceSystemId())) {
                resolution.resolve(
                        tenant,
                        previousIdentityId,
                        mapping.canonicalKey(),
                        now,
                        correlationId,
                        causationId);
            }
            return null;
        });
    }

    private JsonNode payload(String value) {
        try {
            JsonNode parsed = json.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("fact payload must be an object");
            }
            return parsed;
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("fact payload is invalid JSON", invalid);
        }
    }

    private static UUID requiredUuid(JsonNode payload, String name) {
        JsonNode node = payload.get(name);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            return UUID.fromString(node.textValue());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " is invalid", invalid);
        }
    }

    private static UUID optionalUuid(JsonNode payload, String name) {
        JsonNode node = payload.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        try {
            return UUID.fromString(node.textValue());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " is invalid", invalid);
        }
    }

    private static IdentityProfile profile(IdentityType type) {
        return switch (type) {
            case PERSON -> new IdentityProfile.PersonProfile();
            case SERVICE -> new IdentityProfile.ServiceProfile();
            case WORKLOAD -> new IdentityProfile.WorkloadProfile();
        };
    }

    public record BatchResult(int claimed, int processed, int failed) {
        public BatchResult {
            if (claimed < 0 || processed < 0 || failed < 0 || processed + failed != claimed) {
                throw new IllegalArgumentException("invalid source Identity processing batch counts");
            }
        }
    }
}
