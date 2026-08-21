package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.CanonicalAttributeRepository;
import io.wyrmgate.iam.identity.domain.AttributeAuthorityRuleVersion;
import io.wyrmgate.iam.identity.domain.AttributeDefinition;
import io.wyrmgate.iam.identity.domain.AttributeDefinitionVersion;
import io.wyrmgate.iam.identity.domain.AttributeMappingVersion;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCandidate;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCardinality;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeOverride;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeType;
import io.wyrmgate.iam.identity.domain.CanonicalSchemaVersion;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.OptimisticUpdate;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for Identity-owned governed canonical attribute configuration and resolution state. */
public final class JdbcCanonicalAttributeRepository implements CanonicalAttributeRepository {

    private final JdbcTemplate jdbc;
    private final IdGenerator ids;

    public JdbcCanonicalAttributeRepository(JdbcTemplate jdbc, IdGenerator ids) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void insertSchemaVersion(TenantContext tenant, CanonicalSchemaVersion schema) {
        jdbc.update("""
                INSERT INTO identity.canonical_schema_version
                    (id, tenant_id, version_number, state, created_at, activated_at, superseded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, schema.id(), tenant.tenantId(), schema.versionNumber(), schema.state().name(),
                timestamp(schema.createdAt()), timestamp(schema.activatedAt()), timestamp(schema.supersededAt()));
    }

    @Override
    public Optional<CanonicalSchemaVersion> findSchemaVersion(TenantContext tenant, UUID schemaVersionId) {
        return jdbc.query("""
                SELECT id, version_number, state, created_at, activated_at, superseded_at
                FROM identity.canonical_schema_version WHERE tenant_id = ? AND id = ?
                """, (rs, rowNum) -> schema(rs), tenant.tenantId(), schemaVersionId).stream().findFirst();
    }

    @Override
    public Optional<CanonicalSchemaVersion> findActiveSchemaVersion(TenantContext tenant) {
        return jdbc.query("""
                SELECT id, version_number, state, created_at, activated_at, superseded_at
                FROM identity.canonical_schema_version WHERE tenant_id = ? AND state = 'ACTIVE'
                """, (rs, rowNum) -> schema(rs), tenant.tenantId()).stream().findFirst();
    }

    @Override
    public CanonicalSchemaVersion activateSchemaVersion(TenantContext tenant, UUID schemaVersionId, Instant activatedAt) {
        jdbc.update("""
                UPDATE identity.canonical_schema_version
                SET state = 'SUPERSEDED', superseded_at = ?
                WHERE tenant_id = ? AND state = 'ACTIVE' AND id <> ?
                """, timestamp(activatedAt), tenant.tenantId(), schemaVersionId);
        int affected = jdbc.update("""
                UPDATE identity.canonical_schema_version
                SET state = 'ACTIVE', activated_at = ?
                WHERE tenant_id = ? AND id = ? AND state = 'DRAFT'
                """, timestamp(activatedAt), tenant.tenantId(), schemaVersionId);
        if (affected != 1) throw new IllegalStateException("canonical schema is not an activatable draft");
        return findSchemaVersion(tenant, schemaVersionId).orElseThrow();
    }

    @Override
    public void insertAttributeDefinition(TenantContext tenant, AttributeDefinition definition) {
        jdbc.update("""
                INSERT INTO identity.attribute_definition
                    (id, tenant_id, canonical_key, subject_type, lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, 'IDENTITY', ?, ?, ?, ?)
                """, definition.id(), tenant.tenantId(), definition.canonicalKey(), definition.lifecycleState().name(),
                definition.revision(), timestamp(definition.createdAt()), timestamp(definition.updatedAt()));
    }

    @Override
    public Optional<AttributeDefinition> findAttributeDefinitionByKey(TenantContext tenant, String canonicalKey) {
        return jdbc.query("""
                SELECT id, canonical_key, lifecycle_state, revision, created_at, updated_at
                FROM identity.attribute_definition WHERE tenant_id = ? AND canonical_key = ?
                """, (rs, rowNum) -> new AttributeDefinition(
                rs.getObject("id", UUID.class), rs.getString("canonical_key"),
                AttributeDefinition.LifecycleState.valueOf(rs.getString("lifecycle_state")), rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                tenant.tenantId(), canonicalKey).stream().findFirst();
    }

    @Override
    public void insertAttributeDefinitionVersion(TenantContext tenant, AttributeDefinitionVersion version) {
        jdbc.update("""
                INSERT INTO identity.attribute_definition_version
                    (id, tenant_id, schema_version_id, attribute_definition_id, data_type, cardinality,
                     classification, queryable, searchable, policy_addressable, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, version.id(), tenant.tenantId(), version.schemaVersionId(), version.attributeDefinitionId(),
                version.dataType().name(), version.cardinality().name(), version.classification(), version.queryable(),
                version.searchable(), version.policyAddressable(), timestamp(version.createdAt()));
    }

    @Override
    public Optional<AttributeDefinitionVersion> findAttributeDefinitionVersion(
            TenantContext tenant, UUID schemaVersionId, UUID attributeDefinitionId) {
        return jdbc.query("""
                SELECT id, schema_version_id, attribute_definition_id, data_type, cardinality,
                       classification, queryable, searchable, policy_addressable, created_at
                FROM identity.attribute_definition_version
                WHERE tenant_id = ? AND schema_version_id = ? AND attribute_definition_id = ?
                """, (rs, rowNum) -> definitionVersion(rs), tenant.tenantId(), schemaVersionId, attributeDefinitionId)
                .stream().findFirst();
    }

    @Override
    public AttributeMappingVersion replaceActiveMapping(
            TenantContext tenant, UUID sourceSystemId, UUID definitionVersionId, String sourcePath,
            Instant activatedAt, UUID newMappingId) {
        lockSource(tenant, sourceSystemId);
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(max(version_number), 0) + 1 FROM identity.attribute_mapping_version
                WHERE tenant_id = ? AND source_system_id = ? AND attribute_definition_version_id = ?
                """, Long.class, tenant.tenantId(), sourceSystemId, definitionVersionId);
        jdbc.update("""
                UPDATE identity.attribute_mapping_version SET state = 'SUPERSEDED', superseded_at = ?
                WHERE tenant_id = ? AND source_system_id = ? AND attribute_definition_version_id = ? AND state = 'ACTIVE'
                """, timestamp(activatedAt), tenant.tenantId(), sourceSystemId, definitionVersionId);
        jdbc.update("""
                INSERT INTO identity.attribute_mapping_version
                    (id, tenant_id, source_system_id, attribute_definition_version_id, version_number,
                     source_path, state, created_at, activated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, newMappingId, tenant.tenantId(), sourceSystemId, definitionVersionId, next,
                sourcePath, timestamp(activatedAt), timestamp(activatedAt));
        return findActiveMapping(tenant, sourceSystemId, definitionVersionId).orElseThrow();
    }

    @Override
    public Optional<AttributeMappingVersion> findActiveMapping(
            TenantContext tenant, UUID sourceSystemId, UUID definitionVersionId) {
        return jdbc.query("""
                SELECT id, source_system_id, attribute_definition_version_id, version_number,
                       source_path, state, created_at, activated_at, superseded_at
                FROM identity.attribute_mapping_version
                WHERE tenant_id = ? AND source_system_id = ? AND attribute_definition_version_id = ? AND state = 'ACTIVE'
                """, (rs, rowNum) -> mapping(rs), tenant.tenantId(), sourceSystemId, definitionVersionId)
                .stream().findFirst();
    }

    @Override
    public AttributeAuthorityRuleVersion replaceActiveAuthorityRule(
            TenantContext tenant, UUID definitionVersionId, UUID sourceSystemId, int priority,
            Instant activatedAt, UUID newRuleId) {
        lockSource(tenant, sourceSystemId);
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(max(version_number), 0) + 1 FROM identity.attribute_authority_rule_version
                WHERE tenant_id = ? AND attribute_definition_version_id = ? AND source_system_id = ?
                """, Long.class, tenant.tenantId(), definitionVersionId, sourceSystemId);
        jdbc.update("""
                UPDATE identity.attribute_authority_rule_version SET state = 'SUPERSEDED', superseded_at = ?
                WHERE tenant_id = ? AND attribute_definition_version_id = ? AND source_system_id = ? AND state = 'ACTIVE'
                """, timestamp(activatedAt), tenant.tenantId(), definitionVersionId, sourceSystemId);
        jdbc.update("""
                INSERT INTO identity.attribute_authority_rule_version
                    (id, tenant_id, attribute_definition_version_id, source_system_id, version_number,
                     priority, state, created_at, activated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, newRuleId, tenant.tenantId(), definitionVersionId, sourceSystemId, next, priority,
                timestamp(activatedAt), timestamp(activatedAt));
        return findActiveAuthorityRules(tenant, definitionVersionId).stream()
                .filter(rule -> rule.sourceSystemId().equals(sourceSystemId)).findFirst().orElseThrow();
    }

    @Override
    public List<AttributeAuthorityRuleVersion> findActiveAuthorityRules(TenantContext tenant, UUID definitionVersionId) {
        return jdbc.query("""
                SELECT id, attribute_definition_version_id, source_system_id, version_number,
                       priority, state, created_at, activated_at, superseded_at
                FROM identity.attribute_authority_rule_version
                WHERE tenant_id = ? AND attribute_definition_version_id = ? AND state = 'ACTIVE'
                """, (rs, rowNum) -> authority(rs), tenant.tenantId(), definitionVersionId);
    }

    @Override
    public CanonicalAttributeCandidate upsertCandidate(
            TenantContext tenant, UUID identityId, UUID definitionVersionId, UUID sourceSystemId,
            UUID sourceRecordId, UUID mappingVersionId, String sourcePath, Instant sourceUpdatedAt,
            Instant observedAt, List<CanonicalValue> values) {
        UUID proposedId = ids.nextId();
        jdbc.update("""
                INSERT INTO identity.canonical_attribute_candidate
                    (id, tenant_id, identity_id, attribute_definition_version_id, source_system_id,
                     source_record_id, mapping_version_id, source_path, candidate_revision,
                     source_updated_at, observed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT (tenant_id, identity_id, attribute_definition_version_id, source_record_id, mapping_version_id)
                DO UPDATE SET candidate_revision = identity.canonical_attribute_candidate.candidate_revision + 1,
                              source_updated_at = EXCLUDED.source_updated_at,
                              observed_at = EXCLUDED.observed_at,
                              updated_at = EXCLUDED.updated_at
                """, proposedId, tenant.tenantId(), identityId, definitionVersionId, sourceSystemId,
                sourceRecordId, mappingVersionId, sourcePath, timestamp(sourceUpdatedAt),
                timestamp(observedAt), timestamp(observedAt));
        CanonicalAttributeCandidate candidate = findCandidate(
                tenant, identityId, definitionVersionId, sourceRecordId, mappingVersionId).orElseThrow();
        replaceValues("canonical_attribute_candidate_value", "candidate_id", tenant, candidate.id(),
                definitionVersionId, definitionVersion(tenant, definitionVersionId), values);
        return findCandidate(tenant, identityId, definitionVersionId, sourceRecordId, mappingVersionId).orElseThrow();
    }

    @Override
    public List<CanonicalAttributeCandidate> findCandidates(TenantContext tenant, UUID identityId, UUID definitionVersionId) {
        return jdbc.query("""
                SELECT id, identity_id, attribute_definition_version_id, source_system_id, source_record_id,
                       mapping_version_id, source_path, candidate_revision, source_updated_at, observed_at, updated_at
                FROM identity.canonical_attribute_candidate
                WHERE tenant_id = ? AND identity_id = ? AND attribute_definition_version_id = ?
                ORDER BY id
                """, (rs, rowNum) -> candidate(tenant, rs), tenant.tenantId(), identityId, definitionVersionId);
    }

    @Override
    public Optional<CanonicalAttributeOverride> findActiveOverride(
            TenantContext tenant, UUID identityId, UUID definitionId) {
        return jdbc.query("""
                SELECT id, identity_id, attribute_definition_id, attribute_definition_version_id, state, reason,
                       valid_from, valid_until, revision, created_at, superseded_at, correlation_id, causation_id
                FROM identity.canonical_attribute_override
                WHERE tenant_id = ? AND identity_id = ? AND attribute_definition_id = ? AND state = 'ACTIVE'
                """, (rs, rowNum) -> override(tenant, rs), tenant.tenantId(), identityId, definitionId)
                .stream().findFirst();
    }

    @Override
    public CanonicalAttributeOverride replaceActiveOverride(
            TenantContext tenant, CanonicalAttributeOverride override, Instant supersededAt) {
        lockIdentity(tenant, override.identityId());
        jdbc.update("""
                UPDATE identity.canonical_attribute_override SET state = 'SUPERSEDED', superseded_at = ?
                WHERE tenant_id = ? AND identity_id = ? AND attribute_definition_id = ? AND state = 'ACTIVE'
                """, timestamp(supersededAt), tenant.tenantId(), override.identityId(), override.attributeDefinitionId());
        jdbc.update("""
                INSERT INTO identity.canonical_attribute_override
                    (id, tenant_id, identity_id, attribute_definition_id, attribute_definition_version_id,
                     state, reason, valid_from, valid_until, revision, created_at, superseded_at,
                     correlation_id, causation_id)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, NULL, ?, ?)
                """, override.id(), tenant.tenantId(), override.identityId(), override.attributeDefinitionId(),
                override.attributeDefinitionVersionId(), override.reason(), timestamp(override.validFrom()),
                timestamp(override.validUntil()), override.revision(), timestamp(override.createdAt()),
                override.correlationId(), override.causationId());
        AttributeDefinitionVersion version = definitionVersion(tenant, override.attributeDefinitionVersionId());
        replaceValues("canonical_attribute_override_value", "override_id", tenant, override.id(),
                version.id(), version, override.values());
        return findActiveOverride(tenant, override.identityId(), override.attributeDefinitionId()).orElseThrow();
    }

    @Override
    public Optional<CanonicalAttributeState> findStateForUpdate(
            TenantContext tenant, UUID identityId, UUID definitionId) {
        return jdbc.query("""
                SELECT id, identity_id, attribute_definition_id, attribute_definition_version_id,
                       resolution_status, selected_candidate_id, authority_rule_version_id,
                       value_revision, created_at, updated_at
                FROM identity.canonical_attribute_state
                WHERE tenant_id = ? AND identity_id = ? AND attribute_definition_id = ?
                FOR UPDATE
                """, (rs, rowNum) -> state(tenant, rs), tenant.tenantId(), identityId, definitionId)
                .stream().findFirst();
    }

    @Override
    public CanonicalAttributeState saveState(
            TenantContext tenant, CanonicalAttributeState desired, Long expectedValueRevision,
            AttributeDefinitionVersion definitionVersion) {
        if (expectedValueRevision == null) {
            jdbc.update("""
                    INSERT INTO identity.canonical_attribute_state
                        (id, tenant_id, identity_id, attribute_definition_id, attribute_definition_version_id,
                         resolution_status, selected_candidate_id, authority_rule_version_id,
                         value_revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, desired.id(), tenant.tenantId(), desired.identityId(), desired.attributeDefinitionId(),
                    desired.attributeDefinitionVersionId(), desired.resolutionStatus().name(), desired.selectedCandidateId(),
                    desired.authorityRuleVersionId(), desired.valueRevision(), timestamp(desired.createdAt()),
                    timestamp(desired.updatedAt()));
        } else {
            int affected = jdbc.update("""
                    UPDATE identity.canonical_attribute_state
                    SET attribute_definition_version_id = ?, resolution_status = ?, selected_candidate_id = ?,
                        authority_rule_version_id = ?, value_revision = ?, updated_at = ?
                    WHERE tenant_id = ? AND id = ? AND value_revision = ?
                    """, desired.attributeDefinitionVersionId(), desired.resolutionStatus().name(),
                    desired.selectedCandidateId(), desired.authorityRuleVersionId(), desired.valueRevision(),
                    timestamp(desired.updatedAt()), tenant.tenantId(), desired.id(), expectedValueRevision);
            OptimisticUpdate.requireSingleRow(affected, "canonical_attribute_state", desired.id(), expectedValueRevision);
        }
        replaceValues("canonical_attribute_state_value", "state_id", tenant, desired.id(),
                definitionVersion.id(), definitionVersion, desired.values());
        return findState(tenant, desired.identityId(), desired.attributeDefinitionId()).orElseThrow();
    }

    private Optional<CanonicalAttributeState> findState(TenantContext tenant, UUID identityId, UUID definitionId) {
        return jdbc.query("""
                SELECT id, identity_id, attribute_definition_id, attribute_definition_version_id,
                       resolution_status, selected_candidate_id, authority_rule_version_id,
                       value_revision, created_at, updated_at
                FROM identity.canonical_attribute_state
                WHERE tenant_id = ? AND identity_id = ? AND attribute_definition_id = ?
                """, (rs, rowNum) -> state(tenant, rs), tenant.tenantId(), identityId, definitionId)
                .stream().findFirst();
    }

    private Optional<CanonicalAttributeCandidate> findCandidate(
            TenantContext tenant, UUID identityId, UUID definitionVersionId, UUID sourceRecordId, UUID mappingVersionId) {
        return jdbc.query("""
                SELECT id, identity_id, attribute_definition_version_id, source_system_id, source_record_id,
                       mapping_version_id, source_path, candidate_revision, source_updated_at, observed_at, updated_at
                FROM identity.canonical_attribute_candidate
                WHERE tenant_id = ? AND identity_id = ? AND attribute_definition_version_id = ?
                  AND source_record_id = ? AND mapping_version_id = ?
                """, (rs, rowNum) -> candidate(tenant, rs), tenant.tenantId(), identityId, definitionVersionId,
                sourceRecordId, mappingVersionId).stream().findFirst();
    }

    private CanonicalAttributeCandidate candidate(TenantContext tenant, ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID definitionVersionId = rs.getObject("attribute_definition_version_id", UUID.class);
        return new CanonicalAttributeCandidate(id, rs.getObject("identity_id", UUID.class), definitionVersionId,
                rs.getObject("source_system_id", UUID.class), rs.getObject("source_record_id", UUID.class),
                rs.getObject("mapping_version_id", UUID.class), rs.getString("source_path"),
                rs.getLong("candidate_revision"), instant(rs.getTimestamp("source_updated_at")),
                rs.getTimestamp("observed_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                readValues("canonical_attribute_candidate_value", "candidate_id", tenant, id));
    }

    private CanonicalAttributeState state(TenantContext tenant, ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new CanonicalAttributeState(id, rs.getObject("identity_id", UUID.class),
                rs.getObject("attribute_definition_id", UUID.class),
                rs.getObject("attribute_definition_version_id", UUID.class),
                CanonicalAttributeState.ResolutionStatus.valueOf(rs.getString("resolution_status")),
                rs.getObject("selected_candidate_id", UUID.class), rs.getObject("authority_rule_version_id", UUID.class),
                rs.getLong("value_revision"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                readValues("canonical_attribute_state_value", "state_id", tenant, id));
    }

    private CanonicalAttributeOverride override(TenantContext tenant, ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new CanonicalAttributeOverride(id, rs.getObject("identity_id", UUID.class),
                rs.getObject("attribute_definition_id", UUID.class),
                rs.getObject("attribute_definition_version_id", UUID.class),
                CanonicalAttributeOverride.State.valueOf(rs.getString("state")), rs.getString("reason"),
                instant(rs.getTimestamp("valid_from")), instant(rs.getTimestamp("valid_until")), rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("superseded_at")),
                rs.getObject("correlation_id", UUID.class), rs.getObject("causation_id", UUID.class),
                readValues("canonical_attribute_override_value", "override_id", tenant, id));
    }

    private List<CanonicalValue> readValues(String table, String parentColumn, TenantContext tenant, UUID parentId) {
        return jdbc.query("SELECT data_type, value_string, value_boolean, value_integer, value_decimal, value_date, "
                        + "value_datetime, value_enum_key FROM identity." + table
                        + " WHERE tenant_id = ? AND " + parentColumn + " = ? ORDER BY value_ordinal",
                (rs, rowNum) -> value(rs), tenant.tenantId(), parentId);
    }

    private void replaceValues(
            String table, String parentColumn, TenantContext tenant, UUID parentId, UUID definitionVersionId,
            AttributeDefinitionVersion version, List<CanonicalValue> values) {
        version.validateValues(values);
        jdbc.update("DELETE FROM identity." + table + " WHERE tenant_id = ? AND " + parentColumn + " = ?",
                tenant.tenantId(), parentId);
        for (int ordinal = 0; ordinal < values.size(); ordinal++) {
            CanonicalValue value = values.get(ordinal);
            Object[] columns = valueColumns(value);
            jdbc.update("INSERT INTO identity." + table + " (id, tenant_id, " + parentColumn
                            + ", attribute_definition_version_id, data_type, cardinality, value_ordinal, "
                            + "value_string, value_boolean, value_integer, value_decimal, value_date, value_datetime, value_enum_key) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ids.nextId(), tenant.tenantId(), parentId, definitionVersionId, version.dataType().name(),
                    version.cardinality().name(), ordinal, columns[0], columns[1], columns[2], columns[3],
                    columns[4], columns[5], columns[6]);
        }
    }

    private static Object[] valueColumns(CanonicalValue value) {
        Object[] columns = new Object[7];
        switch (value) {
            case CanonicalValue.StringValue v -> columns[0] = v.value();
            case CanonicalValue.BooleanValue v -> columns[1] = v.value();
            case CanonicalValue.IntegerValue v -> columns[2] = v.value();
            case CanonicalValue.DecimalValue v -> columns[3] = v.value();
            case CanonicalValue.DateValue v -> columns[4] = Date.valueOf(v.value());
            case CanonicalValue.DateTimeValue v -> columns[5] = Timestamp.from(v.value());
            case CanonicalValue.EnumValue v -> columns[6] = v.key();
        }
        return columns;
    }

    private static CanonicalValue value(ResultSet rs) throws SQLException {
        return switch (CanonicalAttributeType.valueOf(rs.getString("data_type"))) {
            case STRING -> new CanonicalValue.StringValue(rs.getString("value_string"));
            case BOOLEAN -> new CanonicalValue.BooleanValue(rs.getBoolean("value_boolean"));
            case INTEGER -> new CanonicalValue.IntegerValue(rs.getLong("value_integer"));
            case DECIMAL -> new CanonicalValue.DecimalValue(rs.getObject("value_decimal", BigDecimal.class));
            case DATE -> new CanonicalValue.DateValue(rs.getDate("value_date").toLocalDate());
            case DATETIME -> new CanonicalValue.DateTimeValue(rs.getTimestamp("value_datetime").toInstant());
            case ENUM -> new CanonicalValue.EnumValue(rs.getString("value_enum_key"));
        };
    }

    private AttributeDefinitionVersion definitionVersion(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT id, schema_version_id, attribute_definition_id, data_type, cardinality,
                       classification, queryable, searchable, policy_addressable, created_at
                FROM identity.attribute_definition_version WHERE tenant_id = ? AND id = ?
                """, (rs, rowNum) -> definitionVersion(rs), tenant.tenantId(), id).stream().findFirst().orElseThrow();
    }

    private static AttributeDefinitionVersion definitionVersion(ResultSet rs) throws SQLException {
        return new AttributeDefinitionVersion(rs.getObject("id", UUID.class), rs.getObject("schema_version_id", UUID.class),
                rs.getObject("attribute_definition_id", UUID.class),
                CanonicalAttributeType.valueOf(rs.getString("data_type")),
                CanonicalAttributeCardinality.valueOf(rs.getString("cardinality")), rs.getString("classification"),
                rs.getBoolean("queryable"), rs.getBoolean("searchable"), rs.getBoolean("policy_addressable"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static CanonicalSchemaVersion schema(ResultSet rs) throws SQLException {
        return new CanonicalSchemaVersion(rs.getObject("id", UUID.class), rs.getLong("version_number"),
                CanonicalSchemaVersion.State.valueOf(rs.getString("state")), rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("activated_at")), instant(rs.getTimestamp("superseded_at")));
    }

    private static AttributeMappingVersion mapping(ResultSet rs) throws SQLException {
        return new AttributeMappingVersion(rs.getObject("id", UUID.class), rs.getObject("source_system_id", UUID.class),
                rs.getObject("attribute_definition_version_id", UUID.class), rs.getLong("version_number"),
                rs.getString("source_path"), AttributeMappingVersion.State.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("activated_at").toInstant(),
                instant(rs.getTimestamp("superseded_at")));
    }

    private static AttributeAuthorityRuleVersion authority(ResultSet rs) throws SQLException {
        return new AttributeAuthorityRuleVersion(rs.getObject("id", UUID.class),
                rs.getObject("attribute_definition_version_id", UUID.class), rs.getObject("source_system_id", UUID.class),
                rs.getLong("version_number"), rs.getInt("priority"),
                AttributeAuthorityRuleVersion.State.valueOf(rs.getString("state")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("activated_at").toInstant(), instant(rs.getTimestamp("superseded_at")));
    }

    private void lockSource(TenantContext tenant, UUID sourceSystemId) {
        List<UUID> rows = jdbc.query("SELECT id FROM identity.source_system WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class), tenant.tenantId(), sourceSystemId);
        if (rows.isEmpty()) throw new IllegalArgumentException("source system does not exist");
    }

    private void lockIdentity(TenantContext tenant, UUID identityId) {
        List<UUID> rows = jdbc.query("SELECT id FROM identity.identity WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class), tenant.tenantId(), identityId);
        if (rows.isEmpty()) throw new IllegalArgumentException("identity does not exist");
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
