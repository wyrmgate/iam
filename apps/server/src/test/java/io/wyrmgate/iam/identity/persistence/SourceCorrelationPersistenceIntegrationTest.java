package io.wyrmgate.iam.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.SourceCorrelationService;
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
import java.sql.Timestamp;
import java.time.Instant;
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

class SourceCorrelationPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdentityRepository identities;
    private static JdbcSourceCorrelationRepository sources;
    private static IdentityCommandService identityCommands;
    private static SourceCorrelationService sourceCommands;
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
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        identityCommands = new IdentityCommandService(
                identities,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);
        sourceCommands = new SourceCorrelationService(
                sources,
                identities,
                new JdbcSourceCorrelationFactSink(outbox, ids),
                ids,
                transactions);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("7");
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
    void partialImportRefreshesPositiveRecordsWithoutDeletingUnseenObservation() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Partial Import Tenant", now);
        SourceSystem source = sourceCommands.createSourceSystem(
                tenant, "hr", "HR Source", now, ids.nextId(), null);

        SourceImportRun firstRun = sourceCommands.startImport(tenant, source.id(), now.plusSeconds(1));
        SourceRecord first = sourceCommands.observe(
                tenant, firstRun.id(), "employee-1", "{\"name\":\"Ada\"}", null,
                now.plusSeconds(2), ids.nextId(), null);
        SourceRecord second = sourceCommands.observe(
                tenant, firstRun.id(), "employee-2", "{\"name\":\"Grace\"}", null,
                now.plusSeconds(2), ids.nextId(), null);
        sourceCommands.completeImport(
                tenant, firstRun.id(), SourceImportCompleteness.COMPLETE, "cp-1", null,
                now.plusSeconds(3), ids.nextId(), null);

        SourceImportRun partialRun = sourceCommands.startImport(tenant, source.id(), now.plusSeconds(4));
        SourceRecord refreshed = sourceCommands.observe(
                tenant, partialRun.id(), "employee-1", "{\"name\":\"Ada Updated\"}", null,
                now.plusSeconds(5), ids.nextId(), null);
        sourceCommands.completeImport(
                tenant, partialRun.id(), SourceImportCompleteness.PARTIAL, "cp-2", "source timeout",
                now.plusSeconds(6), ids.nextId(), null);

        SourceRecord persistedFirst = sources.findSourceRecord(tenant, first.id()).orElseThrow();
        SourceRecord persistedSecond = sources.findSourceRecord(tenant, second.id()).orElseThrow();
        assertThat(persistedFirst.id()).isEqualTo(refreshed.id());
        assertThat(persistedFirst.lastImportRunId()).isEqualTo(partialRun.id());
        assertThat(persistedFirst.lastCompleteImportRunId()).isEqualTo(firstRun.id());
        assertThat(persistedSecond.lastImportRunId()).isEqualTo(firstRun.id());
        assertThat(persistedSecond.lastCompleteImportRunId()).isEqualTo(firstRun.id());
        assertThat(sourceRecordCount(tenant, source.id())).isEqualTo(2);
    }

    @Test
    void completeImportMarksOnlyRecordsPositivelyObservedInThatRunAsLastComplete() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Complete Import Tenant", now);
        SourceSystem source = sourceCommands.createSourceSystem(
                tenant, "directory", "Directory", now, ids.nextId(), null);

        SourceImportRun firstRun = sourceCommands.startImport(tenant, source.id(), now.plusSeconds(1));
        SourceRecord first = sourceCommands.observe(
                tenant, firstRun.id(), "one", "{\"v\":1}", null,
                now.plusSeconds(2), ids.nextId(), null);
        SourceRecord second = sourceCommands.observe(
                tenant, firstRun.id(), "two", "{\"v\":1}", null,
                now.plusSeconds(2), ids.nextId(), null);
        sourceCommands.completeImport(
                tenant, firstRun.id(), SourceImportCompleteness.COMPLETE, null, null,
                now.plusSeconds(3), ids.nextId(), null);

        SourceImportRun secondRun = sourceCommands.startImport(tenant, source.id(), now.plusSeconds(4));
        sourceCommands.observe(
                tenant, secondRun.id(), "one", "{\"v\":2}", null,
                now.plusSeconds(5), ids.nextId(), null);
        sourceCommands.completeImport(
                tenant, secondRun.id(), SourceImportCompleteness.COMPLETE, null, null,
                now.plusSeconds(6), ids.nextId(), null);

        assertThat(sources.findSourceRecord(tenant, first.id()).orElseThrow().lastCompleteImportRunId())
                .isEqualTo(secondRun.id());
        assertThat(sources.findSourceRecord(tenant, second.id()).orElseThrow().lastCompleteImportRunId())
                .isEqualTo(firstRun.id());
    }

    @Test
    void completedImportRejectsFurtherObservation() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Closed Import Tenant", now);
        SourceSystem source = sourceCommands.createSourceSystem(
                tenant, "closed", "Closed Source", now, ids.nextId(), null);
        SourceImportRun run = sourceCommands.startImport(tenant, source.id(), now.plusSeconds(1));
        sourceCommands.completeImport(
                tenant, run.id(), SourceImportCompleteness.COMPLETE, null, null,
                now.plusSeconds(2), ids.nextId(), null);

        assertThatThrownBy(() -> sourceCommands.observe(
                        tenant,
                        run.id(),
                        "late-record",
                        "{\"late\":true}",
                        null,
                        now.plusSeconds(3),
                        ids.nextId(),
                        null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("running import");
        assertThat(sources.findSourceRecordByNativeKey(tenant, source.id(), "late-record")).isEmpty();
    }

    @Test
    void acceptedCorrelationSupersedesPriorLinkAndPreservesHistory() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Correlation Tenant", now);
        SourceSystem source = sourceCommands.createSourceSystem(
                tenant, "hr", "HR", now, ids.nextId(), null);
        SourceImportRun run = sourceCommands.startImport(tenant, source.id(), now.plusSeconds(1));
        SourceRecord record = sourceCommands.observe(
                tenant, run.id(), "employee-42", "{\"employee\":42}", null,
                now.plusSeconds(2), ids.nextId(), null);
        Identity firstIdentity = identity(tenant, "First Identity", now.plusSeconds(3));
        Identity secondIdentity = identity(tenant, "Second Identity", now.plusSeconds(4));

        var firstLink = sourceCommands.acceptCorrelation(
                tenant, record.id(), firstIdentity.id(), "automatic exact employee id",
                now.plusSeconds(5), ids.nextId(), null);
        var secondLink = sourceCommands.acceptCorrelation(
                tenant, record.id(), secondIdentity.id(), "operator corrected correlation",
                now.plusSeconds(6), ids.nextId(), firstLink.correlationId());

        assertThat(sources.findActiveAcceptedLink(tenant, record.id())).contains(secondLink);
        Integer accepted = jdbc.queryForObject(
                "SELECT count(*) FROM identity.identity_link WHERE tenant_id = ? AND source_record_id = ? AND link_state = 'ACCEPTED'",
                Integer.class, tenant.tenantId(), record.id());
        Integer superseded = jdbc.queryForObject(
                "SELECT count(*) FROM identity.identity_link WHERE tenant_id = ? AND source_record_id = ? AND link_state = 'SUPERSEDED'",
                Integer.class, tenant.tenantId(), record.id());
        assertThat(accepted).isEqualTo(1);
        assertThat(superseded).isEqualTo(1);
        assertThat(secondLink.identityId()).isEqualTo(secondIdentity.id());
    }

    @Test
    void repeatedSameCorrelationIsNoOpWithoutDuplicateFact() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("No-op Correlation Tenant", now);
        SourceSystem source = sourceCommands.createSourceSystem(
                tenant, "noop", "No-op Source", now, ids.nextId(), null);
        SourceImportRun run = sourceCommands.startImport(tenant, source.id(), now.plusSeconds(1));
        SourceRecord record = sourceCommands.observe(
                tenant, run.id(), "subject-1", "{\"subject\":1}", null,
                now.plusSeconds(2), ids.nextId(), null);
        Identity identity = identity(tenant, "Canonical", now.plusSeconds(3));

        UUID correlationId = ids.nextId();
        var first = sourceCommands.acceptCorrelation(
                tenant, record.id(), identity.id(), "exact source key",
                now.plusSeconds(4), correlationId, null);
        long factsAfterFirst = outbox.countPending(tenant);
        var replay = sourceCommands.acceptCorrelation(
                tenant, record.id(), identity.id(), "same target retry",
                now.plusSeconds(5), correlationId, null);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(outbox.countPending(tenant)).isEqualTo(factsAfterFirst);
        Integer links = jdbc.queryForObject(
                "SELECT count(*) FROM identity.identity_link WHERE tenant_id = ? AND source_record_id = ?",
                Integer.class, tenant.tenantId(), record.id());
        assertThat(links).isEqualTo(1);
    }

    @Test
    void compositeReferencesRejectCrossTenantCorrelation() {
        Instant now = Instant.now();
        TenantContext firstTenant = tenant("First Tenant", now);
        TenantContext secondTenant = tenant("Second Tenant", now);
        SourceSystem source = sourceCommands.createSourceSystem(
                firstTenant, "source", "Source", now, ids.nextId(), null);
        SourceImportRun run = sourceCommands.startImport(firstTenant, source.id(), now.plusSeconds(1));
        SourceRecord record = sourceCommands.observe(
                firstTenant, run.id(), "native", "{\"v\":1}", null,
                now.plusSeconds(2), ids.nextId(), null);
        Identity foreignIdentity = identity(secondTenant, "Foreign", now.plusSeconds(3));

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO identity.identity_link (
                            id, tenant_id, source_record_id, identity_id, link_state,
                            linked_at, correlation_reason, correlation_id)
                        VALUES (?, ?, ?, ?, 'ACCEPTED', ?, 'invalid cross tenant', ?)
                        """,
                        ids.nextId(),
                        firstTenant.tenantId(),
                        record.id(),
                        foreignIdentity.id(),
                        Timestamp.from(now.plusSeconds(4)),
                        ids.nextId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sourceObservationAndFactRollbackAtomically() {
        Instant now = Instant.now();
        TenantContext tenant = tenant("Atomic Source Tenant", now);
        SourceSystem source = sourceCommands.createSourceSystem(
                tenant, "atomic", "Atomic", now, ids.nextId(), null);
        SourceImportRun run = sourceCommands.startImport(tenant, source.id(), now.plusSeconds(1));
        JdbcSourceCorrelationFactSink facts = new JdbcSourceCorrelationFactSink(outbox, ids);

        long beforeFacts = outbox.countPending(tenant);
        assertThatThrownBy(() -> transactions.required((Runnable) () -> {
                    SourceRecord record = sources.upsertPositiveObservation(
                            tenant,
                            source.id(),
                            run.id(),
                            "rollback",
                            "{\"rollback\":true}",
                            null,
                            now.plusSeconds(2));
                    facts.sourceRecordObserved(tenant, record, ids.nextId(), null);
                    throw new IllegalStateException("force source rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force source rollback");

        assertThat(sources.findSourceRecordByNativeKey(tenant, source.id(), "rollback")).isEmpty();
        assertThat(outbox.countPending(tenant)).isEqualTo(beforeFacts);
    }

    private static TenantContext tenant(String displayName, Instant now) {
        return new TenantContext(tenants.create(displayName, now).id());
    }

    private static Identity identity(TenantContext tenant, String displayName, Instant now) {
        return identityCommands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                displayName,
                now,
                ids.nextId(),
                null);
    }

    private static int sourceRecordCount(TenantContext tenant, UUID sourceSystemId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM identity.source_record WHERE tenant_id = ? AND source_system_id = ?",
                Integer.class,
                tenant.tenantId(),
                sourceSystemId);
        return count == null ? 0 : count;
    }
}
