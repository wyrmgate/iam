package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.application.CanonicalAttributeResolutionEvaluator.EffectiveResolution;
import io.wyrmgate.iam.identity.domain.AttributeDefinition;
import io.wyrmgate.iam.identity.domain.AttributeDefinitionVersion;
import io.wyrmgate.iam.identity.domain.AttributeMappingVersion;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCandidate;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeOverride;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalSchemaVersion;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import io.wyrmgate.iam.identity.domain.IdentityLink;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Resolves typed canonical values from mapped candidates, authority and explicit governed overrides. */
public final class CanonicalAttributeResolutionService {

    private final CanonicalAttributeRepository repository;
    private final SourceCorrelationRepository sources;
    private final IdentityRepository identities;
    private final CanonicalAttributeFactSink facts;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;
    private final CanonicalAttributeResolutionEvaluator evaluator;

    public CanonicalAttributeResolutionService(
            CanonicalAttributeRepository repository,
            SourceCorrelationRepository sources,
            IdentityRepository identities,
            CanonicalAttributeFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this(repository, sources, identities, facts, ids, transactions,
                new CanonicalAttributeResolutionEvaluator());
    }

    public CanonicalAttributeResolutionService(
            CanonicalAttributeRepository repository,
            SourceCorrelationRepository sources,
            IdentityRepository identities,
            CanonicalAttributeFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions,
            CanonicalAttributeResolutionEvaluator evaluator) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
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
            return resolveInside(tenant, identityId, canonicalKey, now, correlationId, causationId);
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
        CanonicalAttributeState current = repository.findStateForUpdate(
                tenant, identityId, definition.id()).orElse(null);
        CanonicalAttributeOverride override = repository.findActiveOverride(
                tenant, identityId, definition.id()).orElse(null);

        EffectiveResolution effective = evaluator.evaluate(
                current,
                version,
                override,
                repository.findCandidates(tenant, identityId, version.id()),
                repository.findActiveAuthorityRules(tenant, version.id()),
                now);
        if (evaluator.sameOutcome(current, effective)) {
            return current;
        }

        CanonicalAttributeState desired = desired(
                current,
                identityId,
                definition.id(),
                version.id(),
                effective,
                now);
        CanonicalAttributeState persisted = repository.saveState(
                tenant, desired, current == null ? null : current.valueRevision(), version);
        facts.stateChanged(tenant, persisted, canonicalKey, correlationId, causationId);
        return persisted;
    }

    private CanonicalAttributeState desired(
            CanonicalAttributeState current,
            UUID identityId,
            UUID definitionId,
            UUID definitionVersionId,
            EffectiveResolution effective,
            Instant now) {
        return new CanonicalAttributeState(
                current == null ? ids.nextId() : current.id(),
                identityId,
                definitionId,
                definitionVersionId,
                effective.resolutionStatus(),
                effective.selectedCandidateId(),
                effective.authorityRuleVersionId(),
                current == null ? 1 : current.valueRevision() + 1,
                current == null ? now : current.createdAt(),
                now,
                effective.values());
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
