package io.wyrmgate.iam.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditFilter;
import io.wyrmgate.iam.audit.domain.AuditExportOperation;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.export.FileSystemAuditExportArtifactStore;
import io.wyrmgate.iam.audit.persistence.JdbcAuditExportRepository;
import io.wyrmgate.iam.audit.persistence.JdbcAuditRecordRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.IdempotencyConflictException;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
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

class AuditExportPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcAuditRecordRepository records;
    private static JdbcAuditExportRepository exports;
    private static TransactionExecutor transactions;

    @TempDir
    Path artifactRoot;

    private TenantContext tenant;
    private UUID requester;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("56");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        records = new JdbcAuditRecordRepository(jdbc);
        exports = new JdbcAuditExportRepository(jdbc);
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
                    audit.audit_export_operation,
                    audit.audit_record,
                    platform.scheduled_work,
                    platform.idempotency_record,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(tenants.create("Audit Export", NOW).id());
        requester = ids.nextId();
    }

    @Test
    void exportFreezesMembershipStreamsAscendingAndReplaysIdempotently() throws Exception {
        append(
                NOW.minusSeconds(300),
                NOW.minusSeconds(200),
                "identity:create",
                AuditOutcome.SUCCESS);
        append(
                NOW.minusSeconds(100),
                NOW.minusSeconds(50),
                "identity:update",
                AuditOutcome.DENIED);

        AuditExportService service = service();
        RequestFingerprint fingerprint = RequestFingerprint.sha256(
                "same-request".getBytes(StandardCharsets.UTF_8));
        AuditExportOperation requested = service.request(
                tenant,
                requester,
                AuditFilter.none(),
                NOW.minusSeconds(600),
                NOW.plusSeconds(60),
                "audit-export-001",
                fingerprint);

        // Same occurrence window but recorded after the immutable request cutoff: excluded.
        append(
                NOW.minusSeconds(10),
                NOW.plusSeconds(1),
                "identity:late-record",
                AuditOutcome.SUCCESS);

        var result = service.executeAvailable();
        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);

        AuditExportOperation completed = service.find(tenant, requested.id());
        assertThat(completed.state()).isEqualTo(AuditExportOperation.State.SUCCEEDED);
        assertThat(completed.recordCount()).isEqualTo(2);
        assertThat(completed.byteCount()).isPositive();
        assertThat(completed.sha256Hex()).matches("[0-9a-f]{64}");
        assertThat(completed.artifactReference()).isNotBlank();

        try (var download = service.openDownload(tenant, requested.id()).stream()) {
            String body = new String(download.readAllBytes(), StandardCharsets.UTF_8);
            String[] lines = body.strip().split("\n");
            assertThat(lines).hasSize(2);
            assertThat(lines[0]).contains("\"actionType\":\"identity:create\"");
            assertThat(lines[1]).contains("\"actionType\":\"identity:update\"");
            assertThat(body).doesNotContain("identity:late-record");
        }

        AuditExportOperation replay = service.request(
                tenant,
                requester,
                AuditFilter.none(),
                NOW.minusSeconds(600),
                NOW.plusSeconds(60),
                "audit-export-001",
                fingerprint);
        assertThat(replay.id()).isEqualTo(requested.id());

        assertThatThrownBy(() -> service.request(
                        tenant,
                        requester,
                        AuditFilter.none(),
                        NOW.minusSeconds(600),
                        NOW.plusSeconds(60),
                        "audit-export-001",
                        RequestFingerprint.sha256("different".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private AuditExportService service() {
        return new AuditExportService(
                exports,
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
                Duration.ofHours(1),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "audit-export-test");
    }

    private void append(
            Instant occurredAt,
            Instant recordedAt,
            String actionType,
            AuditOutcome outcome) {
        AuditCommandService commands = new AuditCommandService(
                records,
                transactions,
                Clock.fixed(recordedAt, ZoneOffset.UTC));
        commands.append(
                tenant,
                new AuditRecordDraft(
                        ids.nextId(),
                        occurredAt,
                        requester,
                        actionType,
                        "identity",
                        ids.nextId(),
                        outcome,
                        ids.nextId(),
                        null));
    }
}
