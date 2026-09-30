package io.wyrmgate.iam.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.SourceCorrelationService;
import io.wyrmgate.iam.identity.application.SourceLifecyclePolicyService;
import io.wyrmgate.iam.identity.application.SourceMappedValueExtractor;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.domain.SourceImportCompleteness;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class SourceLifecyclePolicyIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdentityRepository identityRepository;
    private static JdbcSourceCorrelationRepository sourceRepository;
    private static IdentityCommandService identities;
    private static SourceCorrelationService sources;
    private static SourceLifecyclePolicyService policies;
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
        identityRepository = new JdbcIdentityRepository(jdbc);
        sourceRepository = new JdbcSourceCorrelationRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        identities = new IdentityCommandService(
                identityRepository, new JdbcIdentityFactSink(outbox, ids), ids, transactions);
        sources = new SourceCorrelationService(
                sourceRepository,
                identityRepository,
                new JdbcSourceCorrelationFactSink(outbox, ids),
                ids,
                transactions);
        policies = new SourceLifecyclePolicyService(
                sourceRepository,
                identityRepository,
                identities,
                new SourceMappedValueExtractor(new ObjectMapper()),
                ids,
                transactions);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("42");
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
    void versionedPolicyActivatesPendingAndSupersedesPriorVersion() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Lifecycle Joiner", now);
        SourceSystem source = source(tenant, "hr", now);
        Identity identity = identity(tenant, "Pending Joiner", IdentityLifecycleState.PENDING, now.plusSeconds(1));
        SourceRecord record = linkedRecord(
                tenant, source, identity, "employee-1",
                "{\"employmentStatus\":\"ACTIVE\"}", now.plusSeconds(2));

        var first = policies.activate(
                tenant,
                source.id(),
                "$.employmentStatus",
                Map.of("ACTIVE", IdentityLifecycleState.ACTIVE),
                now.plusSeconds(3));
        policies.applyCurrentObservation(
                tenant, record.id(), now.plusSeconds(4), ids.nextId(), ids.nextId());

        assertThat(identityRepository.findById(tenant, identity.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.ACTIVE);

        var second = policies.activate(
                tenant,
                source.id(),
                "$.employmentStatus",
                Map.of("ACTIVE", IdentityLifecycleState.ACTIVE, "LEFT", IdentityLifecycleState.INACTIVE),
                now.plusSeconds(5));

        assertThat(second.versionNumber()).isEqualTo(first.versionNumber() + 1);
        assertThat(sourceRepository.findActiveLifecyclePolicy(tenant, source.id()))
                .get()
                .extracting(policy -> policy.id())
                .isEqualTo(second.id());
    }

    @Test
    void explicitPartialImportReductionAppliesAndEmitsEligibilityFact() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Explicit Leaver", now);
        SourceSystem source = source(tenant, "hr", now);
        Identity identity = identity(tenant, "Active Person", IdentityLifecycleState.ACTIVE, now.plusSeconds(1));
        SourceImportRun run = sources.startImport(tenant, source.id(), now.plusSeconds(2));
        SourceRecord record = sources.observe(
                tenant,
                run.id(),
                "employee-2",
                "{\"employmentStatus\":\"TERMINATED\"}",
                now.plusSeconds(3),
                now.plusSeconds(3),
                ids.nextId(),
                null);
        sources.acceptCorrelation(
                tenant, record.id(), identity.id(), "explicit link",
                now.plusSeconds(4), ids.nextId(), null);
        sources.completeImport(
                tenant,
                run.id(),
                SourceImportCompleteness.PARTIAL,
                null,
                "upstream page failed",
                now.plusSeconds(5),
                ids.nextId(),
                null);

        policies.activate(
                tenant,
                source.id(),
                "$.employmentStatus",
                Map.of("TERMINATED", IdentityLifecycleState.DECOMMISSIONED),
                now.plusSeconds(6));
        policies.applyCurrentObservation(
                tenant, record.id(), now.plusSeconds(7), ids.nextId(), ids.nextId());

        assertThat(identityRepository.findById(tenant, identity.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.DECOMMISSIONED);
        Integer reductionFacts = jdbc.queryForObject(
                """
                SELECT count(*) FROM platform.outbox_event
                WHERE tenant_id = ? AND event_type = 'identity.access-eligibility-changed'
                """,
                Integer.class,
                tenant.tenantId());
        assertThat(reductionFacts).isEqualTo(1);
    }

    @Test
    void mappedActiveDoesNotReactivateSuspendedOrInactiveIdentity() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("No Reactivation", now);
        SourceSystem source = source(tenant, "hr", now);
        policies.activate(
                tenant,
                source.id(),
                "$.employmentStatus",
                Map.of("ACTIVE", IdentityLifecycleState.ACTIVE),
                now.plusSeconds(1));

        Identity suspended = identity(
                tenant, "Suspended", IdentityLifecycleState.SUSPENDED, now.plusSeconds(2));
        SourceRecord suspendedRecord = linkedRecord(
                tenant, source, suspended, "employee-3",
                "{\"employmentStatus\":\"ACTIVE\"}", now.plusSeconds(3));
        policies.applyCurrentObservation(
                tenant, suspendedRecord.id(), now.plusSeconds(4), ids.nextId(), ids.nextId());

        Identity inactive = identity(
                tenant, "Inactive", IdentityLifecycleState.INACTIVE, now.plusSeconds(5));
        SourceRecord inactiveRecord = linkedRecord(
                tenant, source, inactive, "employee-4",
                "{\"employmentStatus\":\"ACTIVE\"}", now.plusSeconds(6));
        policies.applyCurrentObservation(
                tenant, inactiveRecord.id(), now.plusSeconds(7), ids.nextId(), ids.nextId());

        assertThat(identityRepository.findById(tenant, suspended.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.SUSPENDED);
        assertThat(identityRepository.findById(tenant, inactive.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.INACTIVE);
    }

    @Test
    void unmappedValueIsNoOpAndDecommissionedIsTerminal() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Lifecycle Noop", now);
        SourceSystem source = source(tenant, "hr", now);
        Map<String, IdentityLifecycleState> mappings = new LinkedHashMap<>();
        mappings.put("ACTIVE", IdentityLifecycleState.ACTIVE);
        mappings.put("LEFT", IdentityLifecycleState.DECOMMISSIONED);
        policies.activate(tenant, source.id(), "$.employmentStatus", mappings, now.plusSeconds(1));

        Identity active = identity(tenant, "Active", IdentityLifecycleState.ACTIVE, now.plusSeconds(2));
        SourceRecord unmapped = linkedRecord(
                tenant, source, active, "employee-5",
                "{\"employmentStatus\":\"UNKNOWN\"}", now.plusSeconds(3));
        policies.applyCurrentObservation(
                tenant, unmapped.id(), now.plusSeconds(4), ids.nextId(), ids.nextId());
        assertThat(identityRepository.findById(tenant, active.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.ACTIVE);

        Identity terminal = identity(
                tenant, "Terminal", IdentityLifecycleState.DECOMMISSIONED, now.plusSeconds(5));
        SourceRecord staleActive = linkedRecord(
                tenant, source, terminal, "employee-6",
                "{\"employmentStatus\":\"ACTIVE\"}", now.plusSeconds(6));
        policies.applyCurrentObservation(
                tenant, staleActive.id(), now.plusSeconds(7), ids.nextId(), ids.nextId());
        assertThat(identityRepository.findById(tenant, terminal.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.DECOMMISSIONED);
    }

    private static TenantContext tenant(String name, Instant now) {
        return new TenantContext(tenants.create(name, now).id());
    }

    private static SourceSystem source(TenantContext tenant, String code, Instant now) {
        return sources.createSourceSystem(tenant, code, code, now, ids.nextId(), null);
    }

    private static Identity identity(
            TenantContext tenant, String name, IdentityLifecycleState state, Instant now) {
        return identities.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                state,
                name,
                now,
                ids.nextId(),
                null);
    }

    private static SourceRecord linkedRecord(
            TenantContext tenant,
            SourceSystem source,
            Identity identity,
            String nativeKey,
            String json,
            Instant now) {
        SourceImportRun run = sources.startImport(tenant, source.id(), now);
        SourceRecord record = sources.observe(
                tenant, run.id(), nativeKey, json, now, now, ids.nextId(), null);
        sources.acceptCorrelation(
                tenant, record.id(), identity.id(), "test accepted link",
                now.plusMillis(1), ids.nextId(), null);
        return record;
    }
}
