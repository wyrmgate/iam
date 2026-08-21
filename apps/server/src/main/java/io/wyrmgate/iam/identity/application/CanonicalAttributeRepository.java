package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.AttributeAuthorityRuleVersion;
import io.wyrmgate.iam.identity.domain.AttributeDefinition;
import io.wyrmgate.iam.identity.domain.AttributeDefinitionVersion;
import io.wyrmgate.iam.identity.domain.AttributeMappingVersion;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCandidate;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeOverride;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalSchemaVersion;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Identity-owned persistence port for governed canonical attribute configuration and resolution state. */
public interface CanonicalAttributeRepository {

    void insertSchemaVersion(TenantContext tenant, CanonicalSchemaVersion schemaVersion);
    Optional<CanonicalSchemaVersion> findSchemaVersion(TenantContext tenant, UUID schemaVersionId);
    Optional<CanonicalSchemaVersion> findActiveSchemaVersion(TenantContext tenant);
    CanonicalSchemaVersion activateSchemaVersion(TenantContext tenant, UUID schemaVersionId, Instant activatedAt);

    void insertAttributeDefinition(TenantContext tenant, AttributeDefinition definition);
    Optional<AttributeDefinition> findAttributeDefinitionByKey(TenantContext tenant, String canonicalKey);
    void insertAttributeDefinitionVersion(TenantContext tenant, AttributeDefinitionVersion version);
    Optional<AttributeDefinitionVersion> findAttributeDefinitionVersion(
            TenantContext tenant, UUID schemaVersionId, UUID attributeDefinitionId);

    AttributeMappingVersion replaceActiveMapping(
            TenantContext tenant,
            UUID sourceSystemId,
            UUID attributeDefinitionVersionId,
            String sourcePath,
            Instant activatedAt,
            UUID newMappingId);
    Optional<AttributeMappingVersion> findActiveMapping(
            TenantContext tenant, UUID sourceSystemId, UUID attributeDefinitionVersionId);

    AttributeAuthorityRuleVersion replaceActiveAuthorityRule(
            TenantContext tenant,
            UUID attributeDefinitionVersionId,
            UUID sourceSystemId,
            int priority,
            Instant activatedAt,
            UUID newRuleId);
    List<AttributeAuthorityRuleVersion> findActiveAuthorityRules(
            TenantContext tenant, UUID attributeDefinitionVersionId);

    CanonicalAttributeCandidate upsertCandidate(
            TenantContext tenant,
            UUID identityId,
            UUID attributeDefinitionVersionId,
            UUID sourceSystemId,
            UUID sourceRecordId,
            UUID mappingVersionId,
            String sourcePath,
            Instant sourceUpdatedAt,
            Instant observedAt,
            List<CanonicalValue> values);
    List<CanonicalAttributeCandidate> findCandidates(
            TenantContext tenant, UUID identityId, UUID attributeDefinitionVersionId);

    Optional<CanonicalAttributeOverride> findActiveOverride(
            TenantContext tenant, UUID identityId, UUID attributeDefinitionId);
    CanonicalAttributeOverride replaceActiveOverride(
            TenantContext tenant, CanonicalAttributeOverride override, Instant supersededAt);

    Optional<CanonicalAttributeState> findStateForUpdate(
            TenantContext tenant, UUID identityId, UUID attributeDefinitionId);
    CanonicalAttributeState saveState(
            TenantContext tenant,
            CanonicalAttributeState desired,
            Long expectedValueRevision,
            AttributeDefinitionVersion definitionVersion);
}
