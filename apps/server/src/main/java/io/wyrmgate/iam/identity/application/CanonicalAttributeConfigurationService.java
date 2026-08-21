package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.AttributeAuthorityRuleVersion;
import io.wyrmgate.iam.identity.domain.AttributeDefinition;
import io.wyrmgate.iam.identity.domain.AttributeDefinitionVersion;
import io.wyrmgate.iam.identity.domain.AttributeMappingVersion;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCardinality;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeType;
import io.wyrmgate.iam.identity.domain.CanonicalSchemaVersion;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Commands for stable definitions, immutable schema versions, mappings and authority rules. */
public final class CanonicalAttributeConfigurationService {

    private final CanonicalAttributeRepository repository;
    private final SourceCorrelationRepository sources;
    private final CanonicalAttributeFactSink facts;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public CanonicalAttributeConfigurationService(
            CanonicalAttributeRepository repository,
            SourceCorrelationRepository sources,
            CanonicalAttributeFactSink facts,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public CanonicalSchemaVersion createDraftSchema(TenantContext tenant, long versionNumber, Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(now, "now");
        CanonicalSchemaVersion schema = new CanonicalSchemaVersion(
                ids.nextId(), versionNumber, CanonicalSchemaVersion.State.DRAFT, now, null, null);
        return transactions.required(() -> {
            repository.insertSchemaVersion(tenant, schema);
            return repository.findSchemaVersion(tenant, schema.id()).orElseThrow();
        });
    }

    public AttributeDefinitionVersion defineAttribute(
            TenantContext tenant,
            UUID schemaVersionId,
            String canonicalKey,
            CanonicalAttributeType dataType,
            CanonicalAttributeCardinality cardinality,
            String classification,
            boolean queryable,
            boolean searchable,
            boolean policyAddressable,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            requireDraftSchema(tenant, schemaVersionId);
            AttributeDefinition definition = repository.findAttributeDefinitionByKey(tenant, canonicalKey)
                    .orElseGet(() -> {
                        AttributeDefinition created = new AttributeDefinition(
                                ids.nextId(), canonicalKey, AttributeDefinition.LifecycleState.ACTIVE, 1, now, now);
                        repository.insertAttributeDefinition(tenant, created);
                        return created;
                    });
            if (definition.lifecycleState() != AttributeDefinition.LifecycleState.ACTIVE) {
                throw new IllegalStateException("retired attribute definition cannot be versioned");
            }
            AttributeDefinitionVersion version = new AttributeDefinitionVersion(
                    ids.nextId(), schemaVersionId, definition.id(), dataType, cardinality, classification,
                    queryable, searchable, policyAddressable, now);
            repository.insertAttributeDefinitionVersion(tenant, version);
            return repository.findAttributeDefinitionVersion(tenant, schemaVersionId, definition.id()).orElseThrow();
        });
    }

    public CanonicalSchemaVersion activateSchema(
            TenantContext tenant,
            UUID schemaVersionId,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        return transactions.required(() -> {
            requireDraftSchema(tenant, schemaVersionId);
            CanonicalSchemaVersion activated = repository.activateSchemaVersion(tenant, schemaVersionId, now);
            facts.schemaActivated(tenant, activated, correlationId, causationId);
            return activated;
        });
    }

    public AttributeMappingVersion activateMapping(
            TenantContext tenant,
            UUID sourceSystemId,
            String canonicalKey,
            String sourcePath,
            Instant now) {
        return transactions.required(() -> {
            requireSource(tenant, sourceSystemId);
            AttributeDefinitionVersion definitionVersion = requireActiveDefinitionVersion(tenant, canonicalKey);
            return repository.replaceActiveMapping(
                    tenant, sourceSystemId, definitionVersion.id(), sourcePath, now, ids.nextId());
        });
    }

    public AttributeAuthorityRuleVersion activateAuthority(
            TenantContext tenant,
            UUID sourceSystemId,
            String canonicalKey,
            int priority,
            Instant now) {
        return transactions.required(() -> {
            requireSource(tenant, sourceSystemId);
            AttributeDefinitionVersion definitionVersion = requireActiveDefinitionVersion(tenant, canonicalKey);
            return repository.replaceActiveAuthorityRule(
                    tenant, definitionVersion.id(), sourceSystemId, priority, now, ids.nextId());
        });
    }

    private void requireDraftSchema(TenantContext tenant, UUID schemaVersionId) {
        CanonicalSchemaVersion schema = repository.findSchemaVersion(tenant, schemaVersionId)
                .orElseThrow(() -> new IllegalArgumentException("canonical schema version does not exist"));
        if (schema.state() != CanonicalSchemaVersion.State.DRAFT) {
            throw new IllegalStateException("activated canonical schema content is immutable");
        }
    }

    private AttributeDefinitionVersion requireActiveDefinitionVersion(TenantContext tenant, String canonicalKey) {
        CanonicalSchemaVersion schema = repository.findActiveSchemaVersion(tenant)
                .orElseThrow(() -> new IllegalStateException("no active canonical schema"));
        AttributeDefinition definition = repository.findAttributeDefinitionByKey(tenant, canonicalKey)
                .orElseThrow(() -> new IllegalArgumentException("attribute definition does not exist"));
        return repository.findAttributeDefinitionVersion(tenant, schema.id(), definition.id())
                .orElseThrow(() -> new IllegalStateException("attribute is not present in active canonical schema"));
    }

    private void requireSource(TenantContext tenant, UUID sourceSystemId) {
        if (sources.findSourceSystem(tenant, sourceSystemId).isEmpty()) {
            throw new IllegalArgumentException("source system does not exist");
        }
    }
}
