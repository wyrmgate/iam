package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.AttributeAuthorityRuleVersion;
import io.wyrmgate.iam.identity.domain.AttributeDefinition;
import io.wyrmgate.iam.identity.domain.AttributeDefinitionVersion;
import io.wyrmgate.iam.identity.domain.AttributeMappingVersion;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCandidate;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeOverride;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState.ResolutionStatus;
import io.wyrmgate.iam.identity.domain.CanonicalSchemaVersion;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves typed canonical values from mapped candidates, authority and explicit governed overrides. */
public final class CanonicalAttributeResolutionService {

    private final CanonicalAttributeRepository repository;
    private final SourceCorrelationRepository sources;
    private final IdentityRepository identities;
    private final CanonicalAttributeFactSink facts;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public CanonicalAttributeResolutionService(
            CanonicalAttributeRepository repository,
            SourceCorrelationRepository sources,
            IdentityRepository identities,
            CanonicalAttributeFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public CanonicalAttributeCandidate recordCandidate(
            TenantContext tenant,
            UUID sourceRecordId,
            String canonicalKey,
            List<CanonicalValue> values,
            Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        return transactions.required(() -> {
            SourceRecord sourceRecord = sources.findSourceRecord(tenant, sourceRecordId)
                    .orElseThrow(() -> new IllegalArgumentException("source record does not exist"));
            IdentityLink link = sources.findActiveAcceptedLink(tenant, sourceRecordId)
                    .orElseThrow(() -> new IllegalStateException("source record is not correlated to an Identity"));
            AttributeDefinitionVersion definitionVersion = requireActiveDefinitionVersion(tenant, canonicalKey);
            definitionVersion.validateValues(values);
            AttributeMappingVersion mapping = repository.findActiveMapping(
                            tenant, sourceRecord.sourceSystemId(), definitionVersion.id())
                    .orElseThrow(() -> new IllegalStateException("no active mapping for source and canonical attribute"));
            return repository.upsertCandidate(
                    tenant,
                    link.identityId(),
                    definitionVersion.id(),
                    sourceRecord.sourceSystemId(),
                    sourceRecord.id(),
                    mapping.id(),
                    mapping.sourcePath(),
                    sourceRecord.sourceUpdatedAt(),
                    observedAt,
                    values);
        });
    }

    public CanonicalAttributeState resolve(
            TenantContext tenant,
            UUID identityId,
            String canonicalKey,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        return transactions.required(() -> resolveInside(
                tenant, identityId, canonicalKey, now, correlationId, causationId));
    }

    public CanonicalAttributeState applyOverride(
            TenantContext tenant,
            UUID identityId,
            String canonicalKey,
            List<CanonicalValue> values,
            String reason,
            Instant validFrom,
            Instant validUntil,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        return transactions.required(() -> {
            if (identities.findById(tenant, identityId).isEmpty()) {
                throw new IllegalArgumentException("identity does not exist");
            }
            AttributeDefinition definition = requireDefinition(tenant, canonicalKey);
            AttributeDefinitionVersion version = requireActiveDefinitionVersion(tenant, canonicalKey);
            version.validateValues(values);
            CanonicalAttributeOverride override = new CanonicalAttributeOverride(
                    ids.nextId(), identityId, definition.id(), version.id(),
                    CanonicalAttributeOverride.State.ACTIVE, reason, validFrom, validUntil, 1,
                    now, null, correlationId, causationId, values);
            CanonicalAttributeOverride persisted = repository.replaceActiveOverride(tenant, override, now);
            facts.overrideApplied(tenant, persisted, canonicalKey);
            return resolveInside(tenant, identityId, canonicalKey, now, correlationId, persisted.id());
        });
    }

    private CanonicalAttributeState resolveInside(
            TenantContext tenant,
            UUID identityId,
            String canonicalKey,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        if (identities.findById(tenant, identityId).isEmpty()) {
            throw new IllegalArgumentException("identity does not exist");
        }
        AttributeDefinition definition = requireDefinition(tenant, canonicalKey);
        AttributeDefinitionVersion version = requireActiveDefinitionVersion(tenant, canonicalKey);
        CanonicalAttributeState current = repository.findStateForUpdate(tenant, identityId, definition.id()).orElse(null);

        CanonicalAttributeState desired;
        CanonicalAttributeOverride override = repository.findActiveOverride(tenant, identityId, definition.id()).orElse(null);
        if (override != null && override.attributeDefinitionVersionId().equals(version.id()) && override.effectiveAt(now)) {
            desired = desired(current, identityId, definition.id(), version.id(), ResolutionStatus.OVERRIDDEN,
                    null, null, override.values(), now);
        } else {
            List<CanonicalAttributeCandidate> candidates = repository.findCandidates(tenant, identityId, version.id());
            desired = resolveCandidates(tenant, current, identityId, definition.id(), version, candidates, now);
        }

        if (sameOutcome(current, desired)) {
            return current;
        }
        CanonicalAttributeState persisted = repository.saveState(
                tenant, desired, current == null ? null : current.valueRevision(), version);
        facts.stateChanged(tenant, persisted, canonicalKey, correlationId, causationId);
        return persisted;
    }

    private CanonicalAttributeState resolveCandidates(
            TenantContext tenant,
            CanonicalAttributeState current,
            UUID identityId,
            UUID definitionId,
            AttributeDefinitionVersion version,
            List<CanonicalAttributeCandidate> candidates,
            Instant now) {
        if (candidates.isEmpty()) {
            return desired(current, identityId, definitionId, version.id(), ResolutionStatus.NO_VALUE,
                    null, null, List.of(), now);
        }
        Map<UUID, AttributeAuthorityRuleVersion> rules = repository.findActiveAuthorityRules(tenant, version.id()).stream()
                .collect(Collectors.toMap(AttributeAuthorityRuleVersion::sourceSystemId, Function.identity()));
        List<CanonicalAttributeCandidate> authorized = candidates.stream()
                .filter(candidate -> rules.containsKey(candidate.sourceSystemId()))
                .toList();
        if (authorized.isEmpty()) {
            return preserveTrusted(current, identityId, definitionId, version.id(), ResolutionStatus.UNRESOLVED, now);
        }
        int bestPriority = authorized.stream()
                .map(candidate -> rules.get(candidate.sourceSystemId()).priority())
                .min(Integer::compareTo)
                .orElseThrow();
        List<CanonicalAttributeCandidate> top = authorized.stream()
                .filter(candidate -> rules.get(candidate.sourceSystemId()).priority() == bestPriority)
                .toList();
        List<CanonicalValue> firstValues = top.getFirst().values();
        boolean conflict = top.stream().anyMatch(candidate -> !candidate.values().equals(firstValues));
        if (conflict) {
            return preserveTrusted(current, identityId, definitionId, version.id(), ResolutionStatus.CONFLICT, now);
        }
        CanonicalAttributeCandidate selected = top.stream()
                .min(Comparator.comparing(candidate -> candidate.sourceSystemId().toString()))
                .orElseThrow();
        AttributeAuthorityRuleVersion rule = rules.get(selected.sourceSystemId());
        return desired(current, identityId, definitionId, version.id(), ResolutionStatus.RESOLVED,
                selected.id(), rule.id(), selected.values(), now);
    }

    private CanonicalAttributeState preserveTrusted(
            CanonicalAttributeState current,
            UUID identityId,
            UUID definitionId,
            UUID definitionVersionId,
            ResolutionStatus status,
            Instant now) {
        if (current != null
                && current.resolutionStatus() != ResolutionStatus.OVERRIDDEN
                && current.attributeDefinitionVersionId().equals(definitionVersionId)
                && !current.values().isEmpty()) {
            return desired(current, identityId, definitionId, definitionVersionId, status,
                    current.selectedCandidateId(), current.authorityRuleVersionId(), current.values(), now);
        }
        return desired(current, identityId, definitionId, definitionVersionId, status, null, null, List.of(), now);
    }

    private CanonicalAttributeState desired(
            CanonicalAttributeState current,
            UUID identityId,
            UUID definitionId,
            UUID definitionVersionId,
            ResolutionStatus status,
            UUID selectedCandidateId,
            UUID authorityRuleId,
            List<CanonicalValue> values,
            Instant now) {
        return new CanonicalAttributeState(
                current == null ? ids.nextId() : current.id(), identityId, definitionId, definitionVersionId,
                status, selectedCandidateId, authorityRuleId,
                current == null ? 1 : current.valueRevision() + 1,
                current == null ? now : current.createdAt(), now, values);
    }

    private boolean sameOutcome(CanonicalAttributeState current, CanonicalAttributeState desired) {
        return current != null
                && current.attributeDefinitionVersionId().equals(desired.attributeDefinitionVersionId())
                && current.resolutionStatus() == desired.resolutionStatus()
                && Objects.equals(current.selectedCandidateId(), desired.selectedCandidateId())
                && Objects.equals(current.authorityRuleVersionId(), desired.authorityRuleVersionId())
                && current.values().equals(desired.values());
    }

    private AttributeDefinition requireDefinition(TenantContext tenant, String canonicalKey) {
        return repository.findAttributeDefinitionByKey(tenant, canonicalKey)
                .orElseThrow(() -> new IllegalArgumentException("attribute definition does not exist"));
    }

    private AttributeDefinitionVersion requireActiveDefinitionVersion(TenantContext tenant, String canonicalKey) {
        CanonicalSchemaVersion schema = repository.findActiveSchemaVersion(tenant)
                .orElseThrow(() -> new IllegalStateException("no active canonical schema"));
        AttributeDefinition definition = requireDefinition(tenant, canonicalKey);
        return repository.findAttributeDefinitionVersion(tenant, schema.id(), definition.id())
                .orElseThrow(() -> new IllegalStateException("attribute is not present in active canonical schema"));
    }
}
