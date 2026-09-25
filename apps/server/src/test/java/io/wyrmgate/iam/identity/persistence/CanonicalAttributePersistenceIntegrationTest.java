package io.wyrmgate.iam.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.identity.application.CanonicalAttributeConfigurationService;
import io.wyrmgate.iam.identity.application.CanonicalAttributeFactSink;
import io.wyrmgate.iam.identity.application.CanonicalAttributeResolutionService;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.SourceCorrelationService;
import io.wyrmgate.iam.identity.domain.AttributeDefinitionVersion;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeCardinality;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeOverride;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeState;
import io.wyrmgate.iam.identity.domain.CanonicalAttributeType;
import io.wyrmgate.iam.identity.domain.CanonicalSchemaVersion;
import io.wyrmgate.iam.identity.domain.CanonicalValue;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.domain.SourceImportRun;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.identity.domain.SourceSystem;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CanonicalAttributePersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdentityRepository identities;
    private static JdbcSourceCorrelationRepository sources;
    private static JdbcCanonicalAttributeRepository attributes;
    private static IdentityCommandService identityCommands;
    private static SourceCorrelationService sourceCommands;
    private static CanonicalAttributeConfigurationService configuration;
    private static CanonicalAttributeResolutionService resolution;
    private static CanonicalAttributeFactSink facts;
    private static TransactionExecutor transactions;

    @BeforeAll
    static void startPostgresAndMigrate() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        outbox = new JdbcOutboxRepository(jdbc);
        identities = new JdbcIdentityRepository(jdbc);
        sources = new JdbcSourceCorrelationRepository(jdbc, ids);
        attributes = new JdbcCanonicalAttributeRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        facts = new JdbcCanonicalAttributeFactSink(outbox, ids);
        identityCommands = new IdentityCommandService(
                identities, new JdbcIdentityFactSink(outbox, ids), ids, transactions);
        sourceCommands = new SourceCorrelationService(
                sources, identities, new JdbcSourceCorrelationFactSink(outbox, ids), ids, transactions);
        configuration = new CanonicalAttributeConfigurationService(
                attributes, sources, facts, ids, transactions);
        resolution = new CanonicalAttributeResolutionService(
                attributes, sources, identities, facts, ids, transactions);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("24");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clearRows() {
        jdbc.execute("""
                TRUNCATE TABLE
                    platform.scheduled_work,
                    platform.idempotency_record,
                    platform.inbox_message,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
    }

    @Test
    void authorityPriorityWinsOverNewerObservationAndRepeatResolutionIsNoOp() {
        Instant now = Instant.parse("2026-08-21T10:00:00Z");
        TenantContext tenant = tenant("Authority Tenant", now);
        Identity identity = identity(tenant, "Ada", now);
        SourceRecord hr = correlatedRecord(tenant, identity, "hr", "employee-1", now.plusSeconds(1));
        SourceRecord directory = correlatedRecord(tenant, identity, "directory", "user-1", now.plusSeconds(2));
        activateStringAttribute(tenant, "jobTitle", now.plusSeconds(3));
        configuration.activateMapping(tenant, hr.sourceSystemId(), "jobTitle", "$.title", now.plusSeconds(4));
        configuration.activateMapping(tenant, directory.sourceSystemId(), "jobTitle", "$.title", now.plusSeconds(4));
        configuration.activateAuthority(tenant, hr.sourceSystemId(), "jobTitle", 10, now.plusSeconds(5));
        configuration.activateAuthority(tenant, directory.sourceSystemId(), "jobTitle", 20, now.plusSeconds(5));

        resolution.recordCandidate(tenant, hr.id(), "jobTitle",
                List.of(new CanonicalValue.StringValue("Engineer")), now.plusSeconds(6));
        resolution.recordCandidate(tenant, directory.id(), "jobTitle",
                List.of(new CanonicalValue.StringValue("Principal Engineer")), now.plusSeconds(20));

        CanonicalAttributeState first = resolution.resolve(
                tenant, identity.id(), "jobTitle", now.plusSeconds(21), ids.nextId(), null);
        assertThat(first.resolutionStatus()).isEqualTo(CanonicalAttributeState.ResolutionStatus.RESOLVED);
        assertThat(first.values()).containsExactly(new CanonicalValue.StringValue("Engineer"));
        long factsAfterFirst = outbox.countPending(tenant);

        CanonicalAttributeState repeated = resolution.resolve(
                tenant, identity.id(), "jobTitle", now.plusSeconds(22), ids.nextId(), null);
        assertThat(repeated.valueRevision()).isEqualTo(first.valueRevision());
        assertThat(outbox.countPending(tenant)).isEqualTo(factsAfterFirst);
    }

    @Test
    void equalTopAuthorityConflictRetainsPriorTrustedSourceValue() {
        Instant now = Instant.parse("2026-08-21T11:00:00Z");
        TenantContext tenant = tenant("Conflict Tenant", now);
        Identity identity = identity(tenant, "Grace", now);
        SourceRecord firstSource = correlatedRecord(tenant, identity, "hr", "employee-2", now.plusSeconds(1));
        SourceRecord secondSource = correlatedRecord(tenant, identity, "directory", "user-2", now.plusSeconds(2));
        activateStringAttribute(tenant, "departmentCode", now.plusSeconds(3));
        configuration.activateMapping(tenant, firstSource.sourceSystemId(), "departmentCode", "$.department", now.plusSeconds(4));
        configuration.activateMapping(tenant, secondSource.sourceSystemId(), "departmentCode", "$.department", now.plusSeconds(4));
        configuration.activateAuthority(tenant, firstSource.sourceSystemId(), "departmentCode", 10, now.plusSeconds(5));
        configuration.activateAuthority(tenant, secondSource.sourceSystemId(), "departmentCode", 20, now.plusSeconds(5));
        resolution.recordCandidate(tenant, firstSource.id(), "departmentCode",
                List.of(new CanonicalValue.StringValue("ENG")), now.plusSeconds(6));
        resolution.recordCandidate(tenant, secondSource.id(), "departmentCode",
                List.of(new CanonicalValue.StringValue("RND")), now.plusSeconds(7));

        CanonicalAttributeState trusted = resolution.resolve(
                tenant, identity.id(), "departmentCode", now.plusSeconds(8), ids.nextId(), null);
        configuration.activateAuthority(tenant, secondSource.sourceSystemId(), "departmentCode", 10, now.plusSeconds(9));
        CanonicalAttributeState conflict = resolution.resolve(
                tenant, identity.id(), "departmentCode", now.plusSeconds(10), ids.nextId(), null);

        assertThat(conflict.resolutionStatus()).isEqualTo(CanonicalAttributeState.ResolutionStatus.CONFLICT);
        assertThat(conflict.values()).isEqualTo(trusted.values());
        assertThat(conflict.selectedCandidateId()).isEqualTo(trusted.selectedCandidateId());
        assertThat(conflict.valueRevision()).isEqualTo(trusted.valueRevision() + 1);
    }

    @Test
    void mappingWithoutAuthorityIsUnresolvedAndNeverUsesLastWriteWins() {
        Instant now = Instant.parse("2026-08-21T12:00:00Z");
        TenantContext tenant = tenant("Unresolved Tenant", now);
        Identity identity = identity(tenant, "Linus", now);
        SourceRecord record = correlatedRecord(tenant, identity, "source", "subject-1", now.plusSeconds(1));
        activateStringAttribute(tenant, "costCenter", now.plusSeconds(2));
        configuration.activateMapping(tenant, record.sourceSystemId(), "costCenter", "$.costCenter", now.plusSeconds(3));
        resolution.recordCandidate(tenant, record.id(), "costCenter",
                List.of(new CanonicalValue.StringValue("CC-100")), now.plusSeconds(4));

        CanonicalAttributeState state = resolution.resolve(
                tenant, identity.id(), "costCenter", now.plusSeconds(5), ids.nextId(), null);
        assertThat(state.resolutionStatus()).isEqualTo(CanonicalAttributeState.ResolutionStatus.UNRESOLVED);
        assertThat(state.values()).isEmpty();
    }

    @Test
    void effectiveOverrideWinsButExpiryImmediatelyReturnsToSourceResolution() {
        Instant now = Instant.parse("2026-08-21T13:00:00Z");
        TenantContext tenant = tenant("Override Tenant", now);
        Identity identity = identity(tenant, "Margaret", now);
        SourceRecord record = correlatedRecord(tenant, identity, "hr", "employee-3", now.plusSeconds(1));
        activateStringAttribute(tenant, "workerClass", now.plusSeconds(2));
        configuration.activateMapping(tenant, record.sourceSystemId(), "workerClass", "$.workerClass", now.plusSeconds(3));
        configuration.activateAuthority(tenant, record.sourceSystemId(), "workerClass", 10, now.plusSeconds(4));
        resolution.recordCandidate(tenant, record.id(), "workerClass",
                List.of(new CanonicalValue.StringValue("EMPLOYEE")), now.plusSeconds(5));
        CanonicalAttributeState sourceState = resolution.resolve(
                tenant, identity.id(), "workerClass", now.plusSeconds(6), ids.nextId(), null);

        CanonicalAttributeState overridden = resolution.applyOverride(
                tenant, identity.id(), "workerClass",
                List.of(new CanonicalValue.StringValue("CONTRACTOR")), "approved temporary correction",
                now.plusSeconds(7), now.plusSeconds(10), now.plusSeconds(7), ids.nextId(), null);
        assertThat(overridden.resolutionStatus()).isEqualTo(CanonicalAttributeState.ResolutionStatus.OVERRIDDEN);
        assertThat(overridden.values()).containsExactly(new CanonicalValue.StringValue("CONTRACTOR"));

        CanonicalAttributeState afterExpiry = resolution.resolve(
                tenant, identity.id(), "workerClass", now.plusSeconds(11), ids.nextId(), null);
        assertThat(afterExpiry.resolutionStatus()).isEqualTo(CanonicalAttributeState.ResolutionStatus.RESOLVED);
        assertThat(afterExpiry.values()).isEqualTo(sourceState.values());
        Integer overrideRows = jdbc.queryForObject(
                "SELECT count(*) FROM identity.canonical_attribute_override WHERE tenant_id = ? AND identity_id = ?",
                Integer.class, tenant.tenantId(), identity.id());
        assertThat(overrideRows).isEqualTo(1);
    }

    @Test
    void activatedSchemaIsImmutableAndReplacementSchemaSupersedesIt() {
        Instant now = Instant.parse("2026-08-21T14:00:00Z");
        TenantContext tenant = tenant("Schema Tenant", now);
        CanonicalSchemaVersion first = configuration.createDraftSchema(tenant, 1, now);
        configuration.defineAttribute(tenant, first.id(), "officeCode", CanonicalAttributeType.STRING,
                CanonicalAttributeCardinality.SINGLE, "INTERNAL", true, true, true, now.plusSeconds(1));
        CanonicalSchemaVersion active = configuration.activateSchema(
                tenant, first.id(), now.plusSeconds(2), ids.nextId(), null);

        assertThatThrownBy(() -> configuration.defineAttribute(
                        tenant, first.id(), "lateField", CanonicalAttributeType.STRING,
                        CanonicalAttributeCardinality.SINGLE, "INTERNAL", false, false, false,
                        now.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable");

        CanonicalSchemaVersion second = configuration.createDraftSchema(tenant, 2, now.plusSeconds(4));
        configuration.defineAttribute(tenant, second.id(), "officeCode", CanonicalAttributeType.STRING,
                CanonicalAttributeCardinality.SINGLE, "INTERNAL", true, true, true, now.plusSeconds(5));
        CanonicalSchemaVersion replacement = configuration.activateSchema(
                tenant, second.id(), now.plusSeconds(6), ids.nextId(), null);

        assertThat(replacement.state()).isEqualTo(CanonicalSchemaVersion.State.ACTIVE);
        assertThat(attributes.findSchemaVersion(tenant, active.id()).orElseThrow().state())
                .isEqualTo(CanonicalSchemaVersion.State.SUPERSEDED);
    }

    @Test
    void multiValuesAreNormalizedRowsAndTypedScalarSetRoundTrips() {
        Instant now = Instant.parse("2026-08-21T15:00:00Z");
        TenantContext tenant = tenant("Typed Tenant", now);
        Identity identity = identity(tenant, "Typed Subject", now);
        SourceRecord record = correlatedRecord(tenant, identity, "typed", "typed-1", now.plusSeconds(1));
        CanonicalSchemaVersion schema = configuration.createDraftSchema(tenant, 1, now.plusSeconds(2));
        define(tenant, schema, "s", CanonicalAttributeType.STRING, CanonicalAttributeCardinality.SINGLE, now);
        define(tenant, schema, "b", CanonicalAttributeType.BOOLEAN, CanonicalAttributeCardinality.SINGLE, now);
        define(tenant, schema, "i", CanonicalAttributeType.INTEGER, CanonicalAttributeCardinality.SINGLE, now);
        define(tenant, schema, "d", CanonicalAttributeType.DECIMAL, CanonicalAttributeCardinality.SINGLE, now);
        define(tenant, schema, "date", CanonicalAttributeType.DATE, CanonicalAttributeCardinality.SINGLE, now);
        define(tenant, schema, "dt", CanonicalAttributeType.DATETIME, CanonicalAttributeCardinality.SINGLE, now);
        define(tenant, schema, "skills", CanonicalAttributeType.ENUM, CanonicalAttributeCardinality.MULTI, now);
        configuration.activateSchema(tenant, schema.id(), now.plusSeconds(4), ids.nextId(), null);

        List<String> keys = List.of("s", "b", "i", "d", "date", "dt", "skills");
        for (String key : keys) {
            configuration.activateMapping(tenant, record.sourceSystemId(), key, "$." + key, now.plusSeconds(5));
            configuration.activateAuthority(tenant, record.sourceSystemId(), key, 1, now.plusSeconds(6));
        }
        List<List<CanonicalValue>> values = List.of(
                List.of(new CanonicalValue.StringValue("text")),
                List.of(new CanonicalValue.BooleanValue(true)),
                List.of(new CanonicalValue.IntegerValue(42)),
                List.of(new CanonicalValue.DecimalValue(new BigDecimal("12.3400"))),
                List.of(new CanonicalValue.DateValue(LocalDate.of(2026, 8, 21))),
                List.of(new CanonicalValue.DateTimeValue(Instant.parse("2026-08-21T15:30:00Z"))),
                List.of(new CanonicalValue.EnumValue("JAVA"), new CanonicalValue.EnumValue("SQL")));
        for (int index = 0; index < keys.size(); index++) {
            resolution.recordCandidate(tenant, record.id(), keys.get(index), values.get(index), now.plusSeconds(7 + index));
            CanonicalAttributeState state = resolution.resolve(
                    tenant, identity.id(), keys.get(index), now.plusSeconds(20 + index), ids.nextId(), null);
            assertThat(state.values()).isEqualTo(values.get(index));
        }

        Integer skillRows = jdbc.queryForObject("""
                SELECT count(*)
                FROM identity.canonical_attribute_state_value v
                JOIN identity.canonical_attribute_state s ON s.tenant_id = v.tenant_id AND s.id = v.state_id
                JOIN identity.attribute_definition d ON d.tenant_id = s.tenant_id AND d.id = s.attribute_definition_id
                WHERE s.tenant_id = ? AND s.identity_id = ? AND d.canonical_key = 'skills'
                """, Integer.class, tenant.tenantId(), identity.id());
        assertThat(skillRows).isEqualTo(2);
    }

    @Test
    void canonicalFactsNeverContainResolvedOrOverrideValues() {
        Instant now = Instant.parse("2026-08-21T16:00:00Z");
        TenantContext tenant = tenant("Minimized Fact Tenant", now);
        Identity identity = identity(tenant, "Sensitive Subject", now);
        SourceRecord record = correlatedRecord(tenant, identity, "sensitive", "subject", now.plusSeconds(1));
        activateStringAttribute(tenant, "sensitiveLabel", now.plusSeconds(2));
        configuration.activateMapping(tenant, record.sourceSystemId(), "sensitiveLabel", "$.secretLabel", now.plusSeconds(3));
        configuration.activateAuthority(tenant, record.sourceSystemId(), "sensitiveLabel", 1, now.plusSeconds(4));
        String sensitiveValue = "TOP-SECRET-CANONICAL-VALUE";
        resolution.recordCandidate(tenant, record.id(), "sensitiveLabel",
                List.of(new CanonicalValue.StringValue(sensitiveValue)), now.plusSeconds(5));
        resolution.resolve(tenant, identity.id(), "sensitiveLabel", now.plusSeconds(6), ids.nextId(), null);
        resolution.applyOverride(tenant, identity.id(), "sensitiveLabel",
                List.of(new CanonicalValue.StringValue("OVERRIDE-SECRET-VALUE")), "governed correction",
                null, null, now.plusSeconds(7), ids.nextId(), null);

        Integer leaked = jdbc.queryForObject("""
                SELECT count(*) FROM platform.outbox_event
                WHERE tenant_id = ? AND (payload::text LIKE ? OR payload::text LIKE ?)
                """, Integer.class, tenant.tenantId(), "%" + sensitiveValue + "%", "%OVERRIDE-SECRET-VALUE%");
        assertThat(leaked).isZero();
    }

    @Test
    void databaseRejectsCrossTenantDefinitionVersionReference() {
        Instant now = Instant.parse("2026-08-21T17:00:00Z");
        TenantContext first = tenant("First Attribute Tenant", now);
        TenantContext second = tenant("Second Attribute Tenant", now);
        CanonicalSchemaVersion foreignSchema = configuration.createDraftSchema(second, 1, now);
        UUID localDefinitionId = ids.nextId();
        jdbc.update("""
                INSERT INTO identity.attribute_definition
                    (id, tenant_id, canonical_key, subject_type, lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, 'local', 'IDENTITY', 'ACTIVE', 1, ?, ?)
                """, localDefinitionId, first.tenantId(), Timestamp.from(now), Timestamp.from(now));

        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO identity.attribute_definition_version
                            (id, tenant_id, schema_version_id, attribute_definition_id, data_type, cardinality,
                             classification, queryable, searchable, policy_addressable, created_at)
                        VALUES (?, ?, ?, ?, 'STRING', 'SINGLE', 'INTERNAL', false, false, false, ?)
                        """, ids.nextId(), first.tenantId(), foreignSchema.id(), localDefinitionId, Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void canonicalStateAndFactRollbackTogether() {
        Instant now = Instant.parse("2026-08-21T18:00:00Z");
        TenantContext tenant = tenant("Rollback Attribute Tenant", now);
        Identity identity = identity(tenant, "Rollback Subject", now);
        SourceRecord record = correlatedRecord(tenant, identity, "rollback", "subject", now.plusSeconds(1));
        activateStringAttribute(tenant, "rollbackField", now.plusSeconds(2));
        configuration.activateMapping(tenant, record.sourceSystemId(), "rollbackField", "$.value", now.plusSeconds(3));
        configuration.activateAuthority(tenant, record.sourceSystemId(), "rollbackField", 1, now.plusSeconds(4));
        resolution.recordCandidate(tenant, record.id(), "rollbackField",
                List.of(new CanonicalValue.StringValue("value")), now.plusSeconds(5));

        CanonicalAttributeFactSink failingFacts = new CanonicalAttributeFactSink() {
            @Override public void schemaActivated(TenantContext t, CanonicalSchemaVersion s, UUID c, UUID cause) { }
            @Override public void stateChanged(TenantContext t, CanonicalAttributeState s, String key, UUID c, UUID cause) {
                throw new IllegalStateException("force canonical rollback");
            }
            @Override public void overrideApplied(TenantContext t, CanonicalAttributeOverride o, String key) { }
        };
        CanonicalAttributeResolutionService failingResolution = new CanonicalAttributeResolutionService(
                attributes, sources, identities, failingFacts, ids, transactions);

        assertThatThrownBy(() -> failingResolution.resolve(
                        tenant, identity.id(), "rollbackField", now.plusSeconds(6), ids.nextId(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force canonical rollback");
        Integer states = jdbc.queryForObject(
                "SELECT count(*) FROM identity.canonical_attribute_state WHERE tenant_id = ? AND identity_id = ?",
                Integer.class, tenant.tenantId(), identity.id());
        assertThat(states).isZero();
    }

    private static void activateStringAttribute(TenantContext tenant, String key, Instant now) {
        CanonicalSchemaVersion schema = configuration.createDraftSchema(tenant, 1, now);
        configuration.defineAttribute(tenant, schema.id(), key, CanonicalAttributeType.STRING,
                CanonicalAttributeCardinality.SINGLE, "INTERNAL", true, true, true, now.plusMillis(1));
        configuration.activateSchema(tenant, schema.id(), now.plusMillis(2), ids.nextId(), null);
    }

    private static void define(
            TenantContext tenant, CanonicalSchemaVersion schema, String key,
            CanonicalAttributeType type, CanonicalAttributeCardinality cardinality, Instant now) {
        configuration.defineAttribute(tenant, schema.id(), key, type, cardinality,
                "INTERNAL", true, false, true, now.plusMillis(1));
    }

    private static TenantContext tenant(String displayName, Instant now) {
        return new TenantContext(tenants.create(displayName, now).id());
    }

    private static Identity identity(TenantContext tenant, String displayName, Instant now) {
        return identityCommands.create(
                tenant, IdentityType.PERSON, new IdentityProfile.PersonProfile(), IdentityLifecycleState.ACTIVE,
                displayName, now, ids.nextId(), null);
    }

    private static SourceRecord correlatedRecord(
            TenantContext tenant, Identity identity, String sourceCode, String nativeKey, Instant now) {
        SourceSystem source = sourceCommands.createSourceSystem(
                tenant, sourceCode, sourceCode + " source", now, ids.nextId(), null);
        SourceImportRun run = sourceCommands.startImport(tenant, source.id(), now.plusMillis(1));
        SourceRecord record = sourceCommands.observe(
                tenant, run.id(), nativeKey, "{\"present\":true}", null, now.plusMillis(2), ids.nextId(), null);
        sourceCommands.acceptCorrelation(
                tenant, record.id(), identity.id(), "test correlation", now.plusMillis(3), ids.nextId(), null);
        return record;
    }
}
