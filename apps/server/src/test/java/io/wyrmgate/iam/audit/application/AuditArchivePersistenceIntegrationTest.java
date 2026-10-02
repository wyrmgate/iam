package io.wyrmgate.iam.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditFilter;
import io.wyrmgate.iam.audit.domain.AuditArchiveSegment;
import io.wyrmgate.iam.audit.domain.AuditExportOperation;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditRetentionPolicyVersion;
import io.wyrmgate.iam.audit.export.FileSystemAuditExportArtifactStore;
import io.wyrmgate.iam.audit.persistence.JdbcAuditArchiveRepository;
import io.wyrmgate.iam.audit.persistence.JdbcAuditExportRepository;
import io.wyrmgate.iam.audit.persistence.JdbcAuditRecordRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class AuditArchivePersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-10-02T04:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcAuditRecordRepository records;
    private static JdbcAuditArchiveRepository archives;
    private static TransactionExecutor transactions;

    @TempDir
    Path artifactRoot;

    private TenantContext tenant;
    private UUID actor;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("54");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        records = new JdbcAuditRecordRepository(jdbc);
        archives = new JdbcAuditArchiveRepository(jdbc);
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
                    audit.audit_archive_segment,
                    audit.audit_retention_policy_version,
                    audit.audit_export_operation,
                    audit.audit_record,
                    platform.scheduled_work,
                    platform.idempotency_record,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(tenants.create("Audit Archive", NOW).id());
        actor = ids.nextId();
    }

    @Test
    void archiveFreezesMembershipVerifiesArtifactAndNeverDeletesSourceRecords() throws Exception {
        AuditArchiveService service = service(new FileSystemAuditExportArtifactStore(artifactRoot), 3);
        AuditRetentionPolicyVersion policy = service.registerPolicy(
                tenant,
                1,
                Duration.ofDays(7),
                Duration.ofDays(1),
                Duration.ofDays(30),
                Duration.ofDays(365),
                NOW.minus(Duration.ofDays(30)));

        Instant from = NOW.minus(Duration.ofDays(10));
        Instant until = NOW.minus(Duration.ofDays(2));
        append(from.plusSeconds(10), NOW.minus(Duration.ofDays(9)), "identity:create");
        append(from.plusSeconds(20), NOW.minus(Duration.ofDays(8)), "identity:update");

        UUID correlationId = ids.nextId();
        AuditArchiveSegment requested = service.request(
                tenant, from, until, correlationId, ids.nextId());

        // Same occurrence range but recorded after the immutable snapshot cutoff: excluded.
        append(from.plusSeconds(30), NOW.plusSeconds(1), "identity:late-record");

        AuditArchiveService.BatchResult result = service.executeAvailable();
        assertThat(result.completed()).isEqualTo(1);

        AuditArchiveSegment completed = service.find(tenant, requested.id());
        assertThat(completed.state()).isEqualTo(AuditArchiveSegment.State.SUCCEEDED);
        assertThat(completed.retentionPolicyVersionId()).isEqualTo(policy.id());
        assertThat(completed.recordCount()).isEqualTo(2);
        assertThat(completed.byteCount()).isPositive();
        assertThat(completed.sha256Hex()).matches("[0-9a-f]{64}");
        assertThat(completed.verifiedAt()).isEqualTo(NOW);
        assertThat(completed.minimumRetainUntil()).isEqualTo(NOW.plus(Duration.ofDays(365)));
        assertThat(completed.artifactReference()).contains("/archive/");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_record WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId())).isEqualTo(3);

        try (InputStream input = new FileSystemAuditExportArtifactStore(artifactRoot)
                .open(tenant, completed.artifactReference())) {
            String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("identity:create", "identity:update");
            assertThat(body).doesNotContain("identity:late-record");
        }

        AuditArchiveSegment replay = service.request(
                tenant, from, until, ids.nextId(), ids.nextId());
        assertThat(replay.id()).isEqualTo(requested.id());
        assertThat(replay.snapshotRecordedAt()).isEqualTo(requested.snapshotRecordedAt());
    }

    @Test
    void retentionPolicyVersionsAreImmutableAndArchiveEligibilityIsEnforced() {
        AuditArchiveService service = service(new FileSystemAuditExportArtifactStore(artifactRoot), 3);
        service.registerPolicy(
                tenant,
                1,
                Duration.ofDays(7),
                Duration.ofDays(1),
                Duration.ofDays(30),
                Duration.ofDays(365),
                NOW.minus(Duration.ofDays(30)));

        assertThatThrownBy(() -> service.registerPolicy(
                        tenant,
                        1,
                        Duration.ofDays(8),
                        Duration.ofDays(1),
                        Duration.ofDays(30),
                        Duration.ofDays(365),
                        NOW.minus(Duration.ofDays(30))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit_retention_policy_version_conflict");

        assertThatThrownBy(() -> service.request(
                        tenant,
                        NOW.minus(Duration.ofHours(12)),
                        NOW.minus(Duration.ofHours(1)),
                        null,
                        null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit_archive_not_eligible");
    }

    @Test
    void retentionPolicyEffectiveAtExportAcceptanceControlsArtifactExpiry() {
        AuditArchiveService archiveService =
                service(new FileSystemAuditExportArtifactStore(artifactRoot), 3);
        archiveService.registerPolicy(
                tenant,
                1,
                Duration.ofDays(7),
                Duration.ofDays(1),
                Duration.ofDays(30),
                Duration.ofDays(365),
                NOW.minus(Duration.ofDays(30)));

        JdbcAuditExportRepository exportRepository = new JdbcAuditExportRepository(jdbc);
        AuditExportService exportService = new AuditExportService(
                exportRepository,
                archives,
                new FileSystemAuditExportArtifactStore(artifactRoot),
                new JdbcIdempotencyRepository(jdbc, ids),
                new JdbcScheduledWorkRepository(jdbc, ids),
                transactions,
                ids,
                new ObjectMapper().findAndRegisterModules(),
                true,
                Duration.ofSeconds(30),
                10,
                3,
                Duration.ofSeconds(5),
                null);

        append(NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)), "identity:create");
        AuditExportOperation requested = exportService.request(
                tenant,
                actor,
                AuditFilter.none(),
                NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofHours(1)),
                "archive-policy-export",
                RequestFingerprint.sha256("archive-policy-export".getBytes(StandardCharsets.UTF_8)));
        assertThat(exportService.executeAvailable().completed()).isEqualTo(1);

        AuditExportOperation completed = exportService.find(tenant, requested.id());
        assertThat(completed.artifactExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void artifactVerificationMismatchFailsClosed() {
        FileSystemAuditExportArtifactStore delegate =
                new FileSystemAuditExportArtifactStore(artifactRoot);
        AuditExportArtifactStore corrupting = new AuditExportArtifactStore() {
            @Override
            public String write(TenantContext tenant, UUID exportId, ArtifactWriter writer) {
                return delegate.write(tenant, exportId, writer);
            }

            @Override
            public String writeArchive(TenantContext tenant, UUID segmentId, ArtifactWriter writer) {
                return delegate.writeArchive(tenant, segmentId, writer);
            }

            @Override
            public InputStream open(TenantContext tenant, String artifactReference) {
                return new ByteArrayInputStream("{\"corrupted\":true}\n"
                        .getBytes(StandardCharsets.UTF_8));
            }
        };

        AuditArchiveService service = service(corrupting, 1);
        service.registerPolicy(
                tenant,
                1,
                Duration.ofDays(7),
                Duration.ofDays(1),
                Duration.ofDays(30),
                Duration.ofDays(365),
                NOW.minus(Duration.ofDays(30)));
        Instant from = NOW.minus(Duration.ofDays(10));
        Instant until = NOW.minus(Duration.ofDays(2));
        append(from.plusSeconds(10), NOW.minus(Duration.ofDays(9)), "identity:create");

        AuditArchiveSegment requested = service.request(tenant, from, until, null, null);
        AuditArchiveService.BatchResult result = service.executeAvailable();

        assertThat(result.failed()).isEqualTo(1);
        AuditArchiveSegment failed = service.find(tenant, requested.id());
        assertThat(failed.state()).isEqualTo(AuditArchiveSegment.State.FAILED);
        assertThat(failed.failureCode()).isEqualTo("audit_archive_processing_failed");
        assertThat(failed.artifactReference()).isNull();
        assertThat(failed.verifiedAt()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_record WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId())).isEqualTo(1);
    }

    private AuditArchiveService service(
            AuditExportArtifactStore artifactStore,
            int maxAttempts) {
        return new AuditArchiveService(
                archives,
                archives,
                artifactStore,
                new JdbcScheduledWorkRepository(jdbc, ids),
                transactions,
                ids,
                new ObjectMapper().findAndRegisterModules(),
                true,
                Duration.ofSeconds(30),
                10,
                maxAttempts,
                Duration.ofSeconds(5),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "audit-archive-test");
    }

    private void append(
            Instant occurredAt,
            Instant recordedAt,
            String actionType) {
        AuditCommandService commands = new AuditCommandService(
                records,
                transactions,
                Clock.fixed(recordedAt, ZoneOffset.UTC));
        commands.append(
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
                        null));
    }
}
