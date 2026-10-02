package io.wyrmgate.iam.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.SourceAbsenceInferenceService;
import io.wyrmgate.iam.identity.application.SourceAbsencePolicyService;
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
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class SourceAbsenceInferenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-30T04:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdentityRepository identityRepository;
    private static JdbcSourceCorrelationRepository sourceRepository;
    private static IdentityCommandService identities;
    private static SourceCorrelationService sources;
    private static SourceAbsencePolicyService policies;
    private static SourceLifecyclePolicyService lifecyclePolicies;
    private static SourceAbsenceInferenceService processor;
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
        policies = new SourceAbsencePolicyService(sourceRepository, ids, transactions);
        lifecyclePolicies = new SourceLifecyclePolicyService(
                sourceRepository,
                identityRepository,
                identities,
                new SourceMappedValueExtractor(new ObjectMapper()),
                ids,
                transactions);
        processor = new SourceAbsenceInferenceService(
                outbox,
                sourceRepository,
                identityRepository,
                identities,
                new JdbcSourceAbsenceInferenceWorkSink(outbox, ids),
                ids,
                transactions,
                new ObjectMapper());

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("58");
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
    void trustedCompleteImportInactivatesOnlyAbsentLinkedIdentity() {
        TenantContext tenant = tenant("Trusted absence");
        SourceSystem source = source(tenant, "hr");
        policies.activate(tenant, source.id(), 10, NOW.plusSeconds(1));

        Identity absent = activeIdentity(tenant, "Absent", NOW.plusSeconds(2));
        Identity present = activeIdentity(tenant, "Present", NOW.plusSeconds(3));
        SourceImportRun baseline = sources.startImport(tenant, source.id(), NOW.plusSeconds(4));
        SourceRecord absentRecord = observe(tenant, baseline, "absent", NOW.plusSeconds(5));
        SourceRecord presentRecord = observe(tenant, baseline, "present", NOW.plusSeconds(5));
        link(tenant, absentRecord, absent, NOW.plusSeconds(6));
        link(tenant, presentRecord, present, NOW.plusSeconds(6));
        sources.completeImport(
                tenant, baseline.id(), SourceImportCompleteness.COMPLETE,
                null, null, NOW.plusSeconds(7), ids.nextId(), null);

        SourceImportRun full = sources.startImport(tenant, source.id(), NOW.plusSeconds(8));
        sources.observe(
                tenant,
                full.id(),
                "present",
                "{\"name\":\"present\"}",
                null,
                NOW.plusSeconds(9),
                ids.nextId(),
                null);
        sources.completeTrustedImport(
                tenant,
                full.id(),
                "adapter confirmed full snapshot and healthy source",
                null,
                NOW.plusSeconds(10),
                ids.nextId(),
                null);

        drainAbsence();

        assertThat(identityRepository.findById(tenant, absent.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.INACTIVE);
        assertThat(identityRepository.findById(tenant, present.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.ACTIVE);
        assertThat(inferenceState(tenant, full.id())).isEqualTo("COMPLETED");

        Integer eligibilityFacts = jdbc.queryForObject(
                """
                SELECT count(*) FROM platform.outbox_event
                WHERE tenant_id = ? AND event_type = 'identity.access-eligibility-changed'
                  AND aggregate_id = ?
                """,
                Integer.class,
                tenant.tenantId(),
                absent.id());
        assertThat(eligibilityFacts).isEqualTo(1);
    }

    @Test
    void returningPositiveObservationRestoresOnlyTheExactAbsenceInferredRevision() {
        TenantContext tenant = tenant("Absence restoration");
        SourceSystem source = source(tenant, "hr");
        policies.activate(tenant, source.id(), 10, NOW.plusSeconds(1));
        lifecyclePolicies.activate(
                tenant,
                source.id(),
                "$.employmentStatus",
                java.util.Map.of("ACTIVE", IdentityLifecycleState.ACTIVE),
                NOW.plusSeconds(2));

        Identity identity = activeIdentity(tenant, "Returning Person", NOW.plusSeconds(3));
        SourceImportRun baseline = sources.startImport(tenant, source.id(), NOW.plusSeconds(4));
        SourceRecord record = sources.observe(
                tenant,
                baseline.id(),
                "returning",
                "{\"employmentStatus\":\"ACTIVE\"}",
                null,
                NOW.plusSeconds(5),
                ids.nextId(),
                null);
        link(tenant, record, identity, NOW.plusSeconds(6));
        sources.completeImport(
                tenant, baseline.id(), SourceImportCompleteness.COMPLETE,
                null, null, NOW.plusSeconds(7), ids.nextId(), null);

        SourceImportRun absent = sources.startImport(tenant, source.id(), NOW.plusSeconds(8));
        sources.completeTrustedImport(
                tenant,
                absent.id(),
                "healthy empty full snapshot",
                null,
                NOW.plusSeconds(9),
                ids.nextId(),
                null);
        drainAbsence();

        Identity inferred = identityRepository.findById(tenant, identity.id()).orElseThrow();
        assertThat(inferred.lifecycleState()).isEqualTo(IdentityLifecycleState.INACTIVE);
        Long evidence = jdbc.queryForObject(
                """
                SELECT count(*) FROM identity.source_absence_transition_evidence
                WHERE tenant_id = ? AND source_record_id = ? AND identity_id = ?
                  AND post_identity_revision = ?
                """,
                Long.class,
                tenant.tenantId(),
                record.id(),
                identity.id(),
                inferred.revision());
        assertThat(evidence).isEqualTo(1L);

        SourceImportRun returning = sources.startImport(tenant, source.id(), NOW.plusSeconds(10));
        SourceRecord observedAgain = sources.observe(
                tenant,
                returning.id(),
                "returning",
                "{\"employmentStatus\":\"ACTIVE\"}",
                null,
                NOW.plusSeconds(11),
                ids.nextId(),
                null);
        lifecyclePolicies.applyCurrentObservation(
                tenant, observedAgain.id(), NOW.plusSeconds(12), ids.nextId(), ids.nextId());

        Identity restored = identityRepository.findById(tenant, identity.id()).orElseThrow();
        assertThat(restored.lifecycleState()).isEqualTo(IdentityLifecycleState.ACTIVE);
        assertThat(restored.revision()).isEqualTo(inferred.revision() + 1);
    }

    @Test
    void completeButUntrustedAndPartialImportsNeverInferAbsence() {
        TenantContext tenant = tenant("No destructive absence");
        SourceSystem source = source(tenant, "hr");
        policies.activate(tenant, source.id(), 10, NOW.plusSeconds(1));
        Identity identity = activeIdentity(tenant, "Still active", NOW.plusSeconds(2));

        SourceImportRun baseline = sources.startImport(tenant, source.id(), NOW.plusSeconds(3));
        SourceRecord record = observe(tenant, baseline, "employee", NOW.plusSeconds(4));
        link(tenant, record, identity, NOW.plusSeconds(5));
        sources.completeImport(
                tenant, baseline.id(), SourceImportCompleteness.COMPLETE,
                null, null, NOW.plusSeconds(6), ids.nextId(), null);

        SourceImportRun untrusted = sources.startImport(tenant, source.id(), NOW.plusSeconds(7));
        sources.completeImport(
                tenant, untrusted.id(), SourceImportCompleteness.COMPLETE,
                null, null, NOW.plusSeconds(8), ids.nextId(), null);

        SourceImportRun partial = sources.startImport(tenant, source.id(), NOW.plusSeconds(9));
        sources.completeImport(
                tenant, partial.id(), SourceImportCompleteness.PARTIAL,
                null, "page timeout", NOW.plusSeconds(10), ids.nextId(), null);

        drainAbsence();

        assertThat(identityRepository.findById(tenant, identity.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.ACTIVE);
        assertThat(inferenceCount(tenant, untrusted.id())).isZero();
        assertThat(inferenceCount(tenant, partial.id())).isZero();
    }

    @Test
    void newerImportSupersedesPendingAbsenceInference() {
        TenantContext tenant = tenant("Newer import fence");
        SourceSystem source = source(tenant, "hr");
        policies.activate(tenant, source.id(), 10, NOW.plusSeconds(1));
        Identity identity = activeIdentity(tenant, "Absent", NOW.plusSeconds(2));

        SourceImportRun baseline = sources.startImport(tenant, source.id(), NOW.plusSeconds(3));
        SourceRecord record = observe(tenant, baseline, "employee", NOW.plusSeconds(4));
        link(tenant, record, identity, NOW.plusSeconds(5));
        sources.completeImport(
                tenant, baseline.id(), SourceImportCompleteness.COMPLETE,
                null, null, NOW.plusSeconds(6), ids.nextId(), null);

        SourceImportRun trusted = sources.startImport(tenant, source.id(), NOW.plusSeconds(7));
        sources.completeTrustedImport(
                tenant, trusted.id(), "healthy full snapshot",
                null, NOW.plusSeconds(8), ids.nextId(), null);

        processor.processAvailable();
        sources.startImport(tenant, source.id(), NOW.plusSeconds(9));
        processor.processAvailable();

        assertThat(identityRepository.findById(tenant, identity.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.ACTIVE);
        assertThat(inferenceState(tenant, trusted.id())).isEqualTo("SUPERSEDED");
    }

    @Test
    void postSnapshotRelinkDoesNotInactivateNewIdentity() {
        TenantContext tenant = tenant("Relink fence");
        SourceSystem source = source(tenant, "hr");
        policies.activate(tenant, source.id(), 10, NOW.plusSeconds(1));
        Identity first = activeIdentity(tenant, "First", NOW.plusSeconds(2));
        Identity replacement = activeIdentity(tenant, "Replacement", NOW.plusSeconds(3));

        SourceImportRun baseline = sources.startImport(tenant, source.id(), NOW.plusSeconds(4));
        SourceRecord record = observe(tenant, baseline, "employee", NOW.plusSeconds(5));
        link(tenant, record, first, NOW.plusSeconds(6));
        sources.completeImport(
                tenant, baseline.id(), SourceImportCompleteness.COMPLETE,
                null, null, NOW.plusSeconds(7), ids.nextId(), null);

        SourceImportRun trusted = sources.startImport(tenant, source.id(), NOW.plusSeconds(8));
        sources.completeTrustedImport(
                tenant, trusted.id(), "healthy full snapshot",
                null, NOW.plusSeconds(9), ids.nextId(), null);

        processor.processAvailable();
        sources.acceptCorrelation(
                tenant,
                record.id(),
                replacement.id(),
                "operator relink during pending inference",
                NOW.plusSeconds(10),
                ids.nextId(),
                null);
        processor.processAvailable();

        assertThat(identityRepository.findById(tenant, replacement.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.ACTIVE);
        assertThat(identityRepository.findById(tenant, first.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.ACTIVE);
    }

    @Test
    void inferencePagesBeyondFiftyAndReplayIsIdempotent() {
        TenantContext tenant = tenant("Paged absence");
        SourceSystem source = source(tenant, "hr");
        policies.activate(tenant, source.id(), 100, NOW.plusSeconds(1));

        SourceImportRun baseline = sources.startImport(tenant, source.id(), NOW.plusSeconds(2));
        java.util.ArrayList<UUID> identityIds = new java.util.ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Identity identity = activeIdentity(
                    tenant, "Person " + i, NOW.plusSeconds(3).plusNanos(i));
            SourceRecord record = sources.observe(
                    tenant,
                    baseline.id(),
                    "employee-" + i,
                    "{\"name\":\"employee-" + i + "\"}",
                    null,
                    NOW.plusSeconds(4).plusNanos(i),
                    ids.nextId(),
                    null);
            link(tenant, record, identity, NOW.plusSeconds(5).plusNanos(i));
            identityIds.add(identity.id());
        }
        sources.completeImport(
                tenant, baseline.id(), SourceImportCompleteness.COMPLETE,
                null, null, NOW.plusSeconds(6), ids.nextId(), null);

        SourceImportRun trusted = sources.startImport(tenant, source.id(), NOW.plusSeconds(7));
        sources.completeTrustedImport(
                tenant, trusted.id(), "healthy empty full snapshot",
                null, NOW.plusSeconds(8), ids.nextId(), null);

        drainAbsence();
        processor.processAvailable();

        Long processed = jdbc.queryForObject(
                """
                SELECT processed_candidate_count
                FROM identity.source_absence_inference
                WHERE tenant_id = ? AND import_run_id = ?
                """,
                Long.class,
                tenant.tenantId(),
                trusted.id());
        Long transitions = jdbc.queryForObject(
                """
                SELECT inferred_transition_count
                FROM identity.source_absence_inference
                WHERE tenant_id = ? AND import_run_id = ?
                """,
                Long.class,
                tenant.tenantId(),
                trusted.id());
        long inactive = identityIds.stream()
                .map(id -> identityRepository.findById(tenant, id).orElseThrow())
                .filter(identity -> identity.lifecycleState() == IdentityLifecycleState.INACTIVE)
                .count();

        assertThat(processed).isEqualTo(55L);
        assertThat(transitions).isEqualTo(55L);
        assertThat(inactive).isEqualTo(55L);
        assertThat(inferenceState(tenant, trusted.id())).isEqualTo("COMPLETED");
        assertThat(inferenceCount(tenant, trusted.id())).isEqualTo(1);
    }

    @Test
    void massLeaverCeilingStopsBeforeSecondTransition() {
        TenantContext tenant = tenant("Mass leaver ceiling");
        SourceSystem source = source(tenant, "hr");
        policies.activate(tenant, source.id(), 1, NOW.plusSeconds(1));
        Identity first = activeIdentity(tenant, "First", NOW.plusSeconds(2));
        Identity second = activeIdentity(tenant, "Second", NOW.plusSeconds(3));

        SourceImportRun baseline = sources.startImport(tenant, source.id(), NOW.plusSeconds(4));
        SourceRecord firstRecord = observe(tenant, baseline, "one", NOW.plusSeconds(5));
        SourceRecord secondRecord = observe(tenant, baseline, "two", NOW.plusSeconds(5));
        link(tenant, firstRecord, first, NOW.plusSeconds(6));
        link(tenant, secondRecord, second, NOW.plusSeconds(6));
        sources.completeImport(
                tenant, baseline.id(), SourceImportCompleteness.COMPLETE,
                null, null, NOW.plusSeconds(7), ids.nextId(), null);

        SourceImportRun trusted = sources.startImport(tenant, source.id(), NOW.plusSeconds(8));
        sources.completeTrustedImport(
                tenant, trusted.id(), "healthy empty full snapshot",
                null, NOW.plusSeconds(9), ids.nextId(), null);

        drainAbsence();

        long inactive = java.util.stream.Stream.of(first, second)
                .map(identity -> identityRepository.findById(tenant, identity.id()).orElseThrow())
                .filter(identity -> identity.lifecycleState() == IdentityLifecycleState.INACTIVE)
                .count();
        assertThat(inactive).isEqualTo(1);
        assertThat(inferenceState(tenant, trusted.id())).isEqualTo("MANUAL_REQUIRED");
        Long transitions = jdbc.queryForObject(
                """
                SELECT inferred_transition_count
                FROM identity.source_absence_inference
                WHERE tenant_id = ? AND import_run_id = ?
                """,
                Long.class,
                tenant.tenantId(),
                trusted.id());
        assertThat(transitions).isEqualTo(1L);
    }

    private static void drainAbsence() {
        for (int i = 0; i < 10; i++) {
            var result = processor.processAvailable();
            if (result.claimed() == 0) {
                return;
            }
        }
    }

    private static TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, NOW).id());
    }

    private static SourceSystem source(TenantContext tenant, String code) {
        return sources.createSourceSystem(tenant, code, code, NOW, ids.nextId(), null);
    }

    private static Identity activeIdentity(TenantContext tenant, String name, Instant at) {
        return identities.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                name,
                at,
                ids.nextId(),
                null);
    }

    private static SourceRecord observe(
            TenantContext tenant, SourceImportRun run, String nativeKey, Instant at) {
        return sources.observe(
                tenant,
                run.id(),
                nativeKey,
                "{\"name\":\"" + nativeKey + "\"}",
                null,
                at,
                ids.nextId(),
                null);
    }

    private static void link(
            TenantContext tenant, SourceRecord record, Identity identity, Instant at) {
        sources.acceptCorrelation(
                tenant,
                record.id(),
                identity.id(),
                "test link",
                at,
                ids.nextId(),
                null);
    }

    private static String inferenceState(TenantContext tenant, UUID runId) {
        return jdbc.queryForObject(
                """
                SELECT process_state
                FROM identity.source_absence_inference
                WHERE tenant_id = ? AND import_run_id = ?
                """,
                String.class,
                tenant.tenantId(),
                runId);
    }

    private static long inferenceCount(TenantContext tenant, UUID runId) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM identity.source_absence_inference
                WHERE tenant_id = ? AND import_run_id = ?
                """,
                Long.class,
                tenant.tenantId(),
                runId);
        return count == null ? 0 : count;
    }
}
