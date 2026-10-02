package io.wyrmgate.iam.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.audit.domain.AuditMaterialSnapshot;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.audit.domain.EvidenceResourceReference;
import io.wyrmgate.iam.audit.domain.EvidenceSnapshot;
import io.wyrmgate.iam.audit.persistence.JdbcAuditRecordRepository;
import io.wyrmgate.iam.audit.persistence.JdbcEvidenceSnapshotRepository;
import io.wyrmgate.iam.audit.siem.AuditSiemDeliveryException;
import io.wyrmgate.iam.audit.siem.AuditSiemDeliveryService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class AuditEvidenceSnapshotSiemPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-10-02T07:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcAuditRecordRepository records;
    private static JdbcEvidenceSnapshotRepository snapshots;
    private static TransactionExecutor transactions;

    private TenantContext tenant;

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
        snapshots = new JdbcEvidenceSnapshotRepository(jdbc);
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
        tenant = new TenantContext(tenants.create("Audit Evidence", NOW).id());
    }

    @Test
    void evidenceSnapshotIsReplaySafeQueryableAndDatabaseImmutable() {
        EvidenceSnapshotService service =
                new EvidenceSnapshotService(snapshots, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID snapshotId = ids.nextId();
        UUID subjectId = ids.nextId();
        EvidenceSnapshotDraft draft = new EvidenceSnapshotDraft(
                snapshotId,
                NOW.minusSeconds(10),
                ids.nextId(),
                "ACCESS_DECISION",
                new EvidenceResourceReference("access-request", subjectId, 4L, "VPN access"),
                new EvidenceResourceReference("policy-version", ids.nextId(), 7L, "Corporate access"),
                new EvidenceResourceReference("entitlement", ids.nextId(), 2L, "VPN"),
                "APPROVED",
                ids.nextId(),
                null);

        EvidenceSnapshot created = service.append(tenant, draft);
        EvidenceSnapshot replay = service.append(tenant, draft);

        assertThat(replay).isEqualTo(created);
        assertThat(service.findById(tenant, snapshotId)).contains(created);
        assertThat(service.list(
                tenant, "ACCESS_DECISION", "access-request", subjectId,
                draft.correlationId(), null, 10).items()).containsExactly(created);

        EvidenceSnapshotDraft conflicting = new EvidenceSnapshotDraft(
                snapshotId,
                draft.occurredAt(),
                draft.actorId(),
                draft.snapshotType(),
                draft.subject(),
                draft.policy(),
                draft.related(),
                "DENIED",
                draft.correlationId(),
                null);
        assertThatThrownBy(() -> service.append(tenant, conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("EvidenceSnapshot replay conflict");

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE audit.evidence_snapshot SET decision_label = 'DENIED' WHERE tenant_id = ? AND id = ?",
                        tenant.tenantId(),
                        snapshotId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM audit.evidence_snapshot WHERE tenant_id = ? AND id = ?",
                        tenant.tenantId(),
                        snapshotId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void auditRecordDerivesIntegrityAndRejectsConflictingMaterialReplay() {
        AuditCommandService service = new AuditCommandService(
                records,
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
        UUID recordId = ids.nextId();
        AuditRecordDraft draft = new AuditRecordDraft(
                recordId,
                NOW.minusSeconds(5),
                ids.nextId(),
                "role:activate",
                "role",
                ids.nextId(),
                AuditOutcome.SUCCESS,
                ids.nextId(),
                null,
                new AuditMaterialSnapshot(null, "Security Admin", 3L, "ACTIVE"));

        AuditRecord created = service.append(tenant, draft);
        assertThat(created.integrityMetadata()).isNotNull();
        assertThat(created.integrityMetadata().contentSha256()).matches("[0-9a-f]{64}");
        assertThat(created.integrityMetadata().materialSnapshotSha256()).matches("[0-9a-f]{64}");
        assertThat(service.append(tenant, draft)).isEqualTo(created);

        AuditRecordDraft conflict = new AuditRecordDraft(
                recordId,
                draft.occurredAt(),
                draft.actorId(),
                draft.actionType(),
                draft.resourceType(),
                draft.resourceId(),
                draft.outcome(),
                draft.correlationId(),
                draft.causationId(),
                new AuditMaterialSnapshot(null, "Renamed Security Admin", 3L, "ACTIVE"));
        assertThatThrownBy(() -> service.append(tenant, conflict))
                .isInstanceOf(AuditRecordConflictException.class);

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO audit.audit_record (
                            id, tenant_id, occurred_at, recorded_at, action_type,
                            resource_type, outcome, material_snapshot)
                        VALUES (?, ?, ?, ?, 'test', 'identity', 'SUCCESS', ?::jsonb)
                        """,
                        ids.nextId(),
                        tenant.tenantId(),
                        java.sql.Timestamp.from(NOW),
                        java.sql.Timestamp.from(NOW),
                        "{\"arbitrary\":true}"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void siemWorkIsIdempotentAndDeliveryStateIsTechnical() {
        JdbcScheduledWorkRepository work = new JdbcScheduledWorkRepository(jdbc, ids);
        AuditCommandService commands = new AuditCommandService(
                records,
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
                work,
                true);
        AuditRecordDraft draft = new AuditRecordDraft(
                ids.nextId(),
                NOW.minusSeconds(1),
                ids.nextId(),
                "credential:revoke",
                "credential",
                ids.nextId(),
                AuditOutcome.SUCCESS,
                ids.nextId(),
                null);

        AuditRecord record = commands.append(tenant, draft);
        commands.append(tenant, draft);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM platform.scheduled_work
                WHERE tenant_id = ? AND handler_type = 'audit.siem' AND work_key = ?
                """,
                Integer.class,
                tenant.tenantId(),
                record.id().toString())).isEqualTo(1);

        AtomicInteger published = new AtomicInteger();
        AuditSiemDeliveryService delivery = new AuditSiemDeliveryService(
                work,
                records,
                message -> published.incrementAndGet(),
                Duration.ofSeconds(30),
                10,
                3,
                Duration.ofSeconds(1));

        assertThat(delivery.deliverAvailable().completed()).isEqualTo(1);
        assertThat(published.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT delivery_state
                FROM platform.scheduled_work
                WHERE tenant_id = ? AND handler_type = 'audit.siem' AND work_key = ?
                """,
                String.class,
                tenant.tenantId(),
                record.id().toString())).isEqualTo("COMPLETED");
    }

    @Test
    void retryableSiemFailureReschedulesWithoutChangingAuditRecord() {
        JdbcScheduledWorkRepository work = new JdbcScheduledWorkRepository(jdbc, ids);
        AuditCommandService commands = new AuditCommandService(
                records,
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
                work,
                true);
        AuditRecord record = commands.append(
                tenant,
                new AuditRecordDraft(
                        ids.nextId(), NOW.minusSeconds(1), ids.nextId(),
                        "identity:suspend", "identity", ids.nextId(),
                        AuditOutcome.SUCCESS, ids.nextId(), null));

        AuditSiemDeliveryService delivery = new AuditSiemDeliveryService(
                work,
                records,
                message -> { throw new AuditSiemDeliveryException(true, "temporary_siem_failure"); },
                Duration.ofSeconds(30),
                10,
                3,
                Duration.ofSeconds(1));

        assertThat(delivery.deliverAvailable().retrying()).isEqualTo(1);
        assertThat(records.findById(tenant, record.id())).contains(record);
        assertThat(jdbc.queryForObject(
                """
                SELECT delivery_state
                FROM platform.scheduled_work
                WHERE tenant_id = ? AND handler_type = 'audit.siem' AND work_key = ?
                """,
                String.class,
                tenant.tenantId(),
                record.id().toString())).isEqualTo("READY");
    }
}
