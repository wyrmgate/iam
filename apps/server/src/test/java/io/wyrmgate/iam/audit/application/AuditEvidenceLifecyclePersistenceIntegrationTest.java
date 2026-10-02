package io.wyrmgate.iam.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.domain.AuditArchiveSegment;
import io.wyrmgate.iam.audit.domain.AuditLegalHold;
import io.wyrmgate.iam.audit.domain.AuditMaterialSnapshot;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditPurgeOperation;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.audit.domain.AuditSelection;
import io.wyrmgate.iam.audit.export.FileSystemAuditExportArtifactStore;
import io.wyrmgate.iam.audit.persistence.JdbcAuditArchiveRepository;
import io.wyrmgate.iam.audit.persistence.JdbcAuditEvidenceLifecycleRepository;
import io.wyrmgate.iam.audit.persistence.JdbcAuditRecordRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class AuditEvidenceLifecyclePersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-10-02T07:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcAuditRecordRepository records;
    private static JdbcAuditArchiveRepository archives;
    private static JdbcAuditEvidenceLifecycleRepository lifecycleRepository;
    private static TransactionExecutor transactions;

    @TempDir
    Path artifactRoot;

    private TenantContext tenant;
    private UUID actor;
    private UUID approver;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("59");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        records = new JdbcAuditRecordRepository(jdbc);
        archives = new JdbcAuditArchiveRepository(jdbc);
        lifecycleRepository = new JdbcAuditEvidenceLifecycleRepository(jdbc);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE
                    audit.evidence_snapshot,
                    audit.audit_purge_operation,
                    audit.audit_archived_record_index,
                    audit.audit_legal_hold,
                    audit.audit_archive_segment,
                    audit.audit_retention_policy_version,
                    audit.audit_export_operation,
                    audit.audit_record,
                    platform.scheduled_work,
                    platform.idempotency_record,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(tenants.create("Audit Lifecycle", NOW).id());
        actor = ids.nextId();
        approver = ids.nextId();
    }

    @Test
    void legalHoldBlocksPurgeAndVerifiedArchivePreservesTransparentReadAfterFencedDelete() {
        JdbcScheduledWorkRepository work = new JdbcScheduledWorkRepository(jdbc, ids);
        FileSystemAuditExportArtifactStore artifacts = new FileSystemAuditExportArtifactStore(artifactRoot);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();

        AuditArchiveService archiveService = new AuditArchiveService(
                archives,
                lifecycleRepository,
                archives,
                artifacts,
                work,
                transactions,
                ids,
                json,
                true,
                Duration.ofSeconds(30),
                10,
                3,
                Duration.ofSeconds(1));

        AuditEvidenceLifecycleService lifecycle = new AuditEvidenceLifecycleService(
                lifecycleRepository,
                archives,
                archives,
                artifacts,
                work,
                transactions,
                ids,
                true,
                Duration.ofSeconds(30),
                5,
                3,
                Duration.ofSeconds(1));

        archiveService.registerPolicy(
                tenant,
                1,
                Duration.ofDays(7),
                Duration.ofDays(1),
                Duration.ofDays(30),
                Duration.ofDays(365),
                NOW.minus(Duration.ofDays(365)));

        Instant from = NOW.minus(Duration.ofDays(100));
        Instant until = NOW.minus(Duration.ofDays(60));
        AuditRecord original = append(
                from.plus(Duration.ofDays(1)),
                NOW.minus(Duration.ofDays(90)),
                "identity:decommission",
                new AuditMaterialSnapshot(
                        "Operator A",
                        "Former Employee",
                        7L,
                        "DECOMMISSIONED"));

        AuditArchiveSegment requested = archiveService.request(tenant, from, until, ids.nextId(), null);
        assertThat(archiveService.executeAvailable().completed()).isEqualTo(1);
        AuditArchiveSegment archived = archiveService.find(tenant, requested.id());
        assertThat(archived.state()).isEqualTo(AuditArchiveSegment.State.SUCCEEDED);
        assertThat(archived.schemaVersion()).isEqualTo(AuditArchiveSegment.NDJSON_V2);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_archived_record_index
                WHERE tenant_id = ? AND archive_segment_id = ? AND record_id = ?
                """,
                Integer.class,
                tenant.tenantId(),
                archived.id(),
                original.id())).isEqualTo(1);

        AuditSelection selection = new AuditSelection(
                from, until, null, null, null, null, null, null);
        AuditLegalHold hold = lifecycle.createHold(
                tenant,
                selection,
                "LITIGATION",
                "CASE-2026-001",
                approver,
                ids.nextId(),
                null);

        assertThatThrownBy(() -> lifecycle.requestPurge(
                        tenant,
                        actor,
                        archived.id(),
                        selection,
                        "RETENTION_EXPIRED",
                        ids.nextId(),
                        null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit_purge_blocked_by_legal_hold");

        lifecycle.releaseHold(tenant, hold.id(), hold.revision(), approver);

        AuditPurgeOperation purge = lifecycle.requestPurge(
                tenant,
                actor,
                archived.id(),
                selection,
                "RETENTION_EXPIRED",
                ids.nextId(),
                null);

        assertThatThrownBy(() ->
                        lifecycle.approvePurge(tenant, purge.id(), purge.revision(), actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("purge requester and approver must differ");

        AuditPurgeOperation approved = lifecycle.approvePurge(
                tenant, purge.id(), purge.revision(), approver);
        assertThat(approved.state()).isEqualTo(AuditPurgeOperation.State.APPROVED);
        assertThat(lifecycle.executeAvailable().completed()).isEqualTo(1);

        AuditPurgeOperation completed = lifecycle.findPurge(tenant, purge.id());
        assertThat(completed.state()).isEqualTo(AuditPurgeOperation.State.SUCCEEDED);
        assertThat(completed.deletedRecordCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_record WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId())).isZero();

        AuditRecord transparent = records.findById(tenant, original.id()).orElseThrow();
        assertThat(transparent.id()).isEqualTo(original.id());
        assertThat(transparent.materialSnapshot()).isEqualTo(original.materialSnapshot());
        assertThat(transparent.integrityMetadata()).isEqualTo(original.integrityMetadata());
    }

    @Test
    void arbitraryAuditRecordDeleteRemainsRejectedWithoutPurgeFence() {
        AuditRecord record = append(
                NOW.minus(Duration.ofDays(100)),
                NOW.minus(Duration.ofDays(99)),
                "identity:create",
                null);

        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM audit.audit_record WHERE tenant_id = ? AND id = ?",
                        tenant.tenantId(),
                        record.id()))
                .isInstanceOf(DataAccessException.class);

        assertThat(records.findById(tenant, record.id())).isPresent();
    }

    private AuditRecord append(
            Instant occurredAt,
            Instant recordedAt,
            String actionType,
            AuditMaterialSnapshot material) {
        AuditCommandService commands = new AuditCommandService(
                records,
                transactions,
                Clock.fixed(recordedAt, ZoneOffset.UTC));
        return commands.append(
                tenant,
                new AuditRecordDraft(
                        ids.nextId(),
                        occurredAt,
                        actor,
                        actionType,
                        "identity",
                        ids.nextId(),
                        AuditOutcome.SUCCESS,
                        ids.nextId(),
                        null,
                        material));
    }
}
