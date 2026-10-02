package io.wyrmgate.iam.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.domain.AuditArchiveSegment;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.export.FileSystemAuditExportArtifactStore;
import io.wyrmgate.iam.audit.persistence.JdbcAuditArchiveRepository;
import io.wyrmgate.iam.audit.persistence.JdbcAuditRecordRepository;
import io.wyrmgate.iam.audit.persistence.JdbcAuditRetentionPolicyRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
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
import org.springframework.dao.DataAccessException;
import org.testcontainers.postgresql.PostgreSQLContainer;

class AuditArchivePersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-10-02T06:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcAuditRecordRepository records;
    private static JdbcAuditArchiveRepository archives;
    private static JdbcAuditRetentionPolicyRepository retentionPolicies;
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
        retentionPolicies = new JdbcAuditRetentionPolicyRepository(jdbc);
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
    void archiveFreezesMembershipVerifiesArtifactAndKeepsTerminalEvidenceImmutable() throws Exception {
        append(NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(39)), "identity:create");
        append(NOW.minus(Duration.ofDays(35)), NOW.minus(Duration.ofDays(34)), "identity:update");

        appendPolicy(Duration.ofDays(7));

        FileSystemAuditExportArtifactStore store = new FileSystemAuditExportArtifactStore(artifactRoot);
        AuditArchiveService service = archiveService(store, 2);
        AuditArchiveSegment requested = service.request(
                tenant,
                NOW.minus(Duration.ofDays(60)),
                NOW.minus(Duration.ofDays(30)),
                ids.nextId(),
                ids.nextId());

        append(NOW.minus(Duration.ofDays(33)), NOW.plusSeconds(1), "identity:late-record");

        var result = service.executeAvailable();
        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);

        AuditArchiveSegment completed = service.find(tenant, requested.id());
        assertThat(completed.state()).isEqualTo(AuditArchiveSegment.State.SUCCEEDED);
        assertThat(completed.retentionPolicyVersion()).isEqualTo(1);
        assertThat(completed.recordCount()).isEqualTo(2);
        assertThat(completed.byteCount()).isPositive();
        assertThat(completed.sha256Hex()).matches("[0-9a-f]{64}");
        assertThat(completed.artifactReference()).isNotBlank();

        try (InputStream input = store.open(tenant, completed.artifactReference())) {
            String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"actionType\":\"identity:create\"");
            assertThat(body).contains("\"actionType\":\"identity:update\"");
            assertThat(body).doesNotContain("identity:late-record");
        }

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit.audit_archive_segment SET byte_count = byte_count + 1 WHERE id = ?",
                completed.id()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void retentionPolicyVersionsAreAppendOnlyAndEffectiveByTime() {
        var first = appendPolicy(Duration.ofDays(7));
        var second = new AuditRetentionPolicyService(
                retentionPolicies,
                transactions,
                ids,
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC))
                .append(
                        tenant,
                        Duration.ofDays(10),
                        Duration.ofDays(14),
                        Duration.ofDays(90),
                        Duration.ofDays(365),
                        NOW.plusSeconds(5),
                        ids.nextId(),
                        first.correlationId());

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(retentionPolicies.findEffective(tenant, NOW).orElseThrow().version()).isEqualTo(1);
        assertThat(retentionPolicies.findEffective(tenant, NOW.plusSeconds(20)).orElseThrow().version()).isEqualTo(2);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit.audit_retention_policy_version SET minimum_archive_retention_ms = 1 WHERE id = ?",
                first.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM audit.audit_retention_policy_version WHERE id = ?",
                second.id()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void archiveVerificationMismatchFailsClosedWithoutCommittedArtifactMetadata() {
        append(NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(39)), "identity:create");
        appendPolicy(Duration.ofDays(7));

        FileSystemAuditExportArtifactStore delegate = new FileSystemAuditExportArtifactStore(artifactRoot);
        AuditExportArtifactStore corrupting = new AuditExportArtifactStore() {
            @Override
            public String write(TenantContext tenant, UUID exportId, ArtifactWriter writer) {
                return delegate.write(tenant, exportId, writer);
            }

            @Override
            public InputStream open(TenantContext tenant, String artifactReference) {
                return new ByteArrayInputStream("corrupted\n".getBytes(StandardCharsets.UTF_8));
            }
        };

        AuditArchiveService service = archiveService(corrupting, 1);
        AuditArchiveSegment requested = service.request(
                tenant,
                NOW.minus(Duration.ofDays(60)),
                NOW.minus(Duration.ofDays(30)),
                ids.nextId(),
                null);

        var result = service.executeAvailable();
        assertThat(result.failed()).isEqualTo(1);

        AuditArchiveSegment failed = service.find(tenant, requested.id());
        assertThat(failed.state()).isEqualTo(AuditArchiveSegment.State.FAILED);
        assertThat(failed.failureCode()).isEqualTo("audit_archive_verification_failed");
        assertThat(failed.artifactReference()).isNull();
        assertThat(failed.sha256Hex()).isNull();
    }

    @Test
    void archiveRejectsRangeThatHasNotReachedConfiguredEligibilityAge() {
        appendPolicy(Duration.ofDays(30));
        AuditArchiveService service = archiveService(
                new FileSystemAuditExportArtifactStore(artifactRoot),
                1);

        assertThatThrownBy(() -> service.request(
                tenant,
                NOW.minus(Duration.ofDays(20)),
                NOW.minus(Duration.ofDays(10)),
                ids.nextId(),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("audit_archive_range_not_yet_eligible");
    }

    private io.wyrmgate.iam.audit.domain.AuditRetentionPolicyVersion appendPolicy(
            Duration archiveEligibilityAge) {
        return new AuditRetentionPolicyService(
                retentionPolicies,
                transactions,
                ids,
                Clock.fixed(NOW, ZoneOffset.UTC))
                .append(
                        tenant,
                        Duration.ofDays(2),
                        archiveEligibilityAge,
                        Duration.ofDays(90),
                        Duration.ofDays(365),
                        NOW.minusSeconds(1),
                        ids.nextId(),
                        null);
    }

    private AuditArchiveService archiveService(
            AuditExportArtifactStore store,
            int maxAttempts) {
        return new AuditArchiveService(
                archives,
                retentionPolicies,
                store,
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
