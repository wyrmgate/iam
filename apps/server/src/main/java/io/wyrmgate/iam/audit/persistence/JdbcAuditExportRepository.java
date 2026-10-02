package io.wyrmgate.iam.audit.persistence;

import io.wyrmgate.iam.audit.application.AuditExportRepository;
import io.wyrmgate.iam.audit.domain.AuditExportOperation;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC persistence for ADR-0034 durable Audit export state and frozen source paging. */
public final class JdbcAuditExportRepository implements AuditExportRepository {

    private final JdbcTemplate jdbc;

    public JdbcAuditExportRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public AuditExportOperation create(
            TenantContext tenant,
            UUID operationId,
            UUID requestedByIdentityId,
            AuditExportOperation.Filter filter,
            Instant occurredFrom,
            Instant occurredUntil,
            Instant snapshotRecordedAt,
            String schemaVersion,
            Instant now) {
        jdbc.update(
                """
                INSERT INTO audit.audit_export_operation (
                    id, tenant_id, requested_by_identity_id,
                    actor_filter_id, action_type_filter, resource_type_filter,
                    resource_id_filter, outcome_filter, correlation_id_filter,
                    occurred_from, occurred_until, snapshot_recorded_at,
                    schema_version, state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', 1, ?, ?)
                """,
                operationId,
                tenant.tenantId(),
                requestedByIdentityId,
                filter.actorId(),
                filter.actionType(),
                filter.resourceType(),
                filter.resourceId(),
                filter.outcome() == null ? null : filter.outcome().name(),
                filter.correlationId(),
                Timestamp.from(occurredFrom),
                Timestamp.from(occurredUntil),
                Timestamp.from(snapshotRecordedAt),
                schemaVersion,
                Timestamp.from(now),
                Timestamp.from(now));
        return find(tenant, operationId).orElseThrow();
    }

    @Override
    public Optional<AuditExportOperation> find(TenantContext tenant, UUID operationId) {
        return jdbc.query(
                        selectOperation() + " WHERE e.tenant_id = ? AND e.id = ?",
                        (rs, rowNum) -> operation(rs),
                        tenant.tenantId(),
                        operationId)
                .stream()
                .findFirst();
    }

    @Override
    public AuditExportOperation beginRun(
            TenantContext tenant,
            UUID operationId,
            long expectedRevision,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_export_operation
                SET state = 'RUNNING',
                    continuation_occurred_at = NULL,
                    continuation_id = NULL,
                    record_count = 0,
                    byte_count = 0,
                    sha256_hex = NULL,
                    artifact_reference = NULL,
                    artifact_expires_at = NULL,
                    failure_code = NULL,
                    completed_at = NULL,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                  AND state IN ('REQUESTED','RUNNING')
                """,
                Timestamp.from(now),
                tenant.tenantId(),
                operationId,
                expectedRevision);
        stale(updated, operationId, expectedRevision);
        return find(tenant, operationId).orElseThrow();
    }

    @Override
    public AuditExportOperation checkpoint(
            TenantContext tenant,
            UUID operationId,
            long expectedRevision,
            Instant continuationOccurredAt,
            UUID continuationId,
            long recordCount,
            long byteCount,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_export_operation
                SET continuation_occurred_at = ?,
                    continuation_id = ?,
                    record_count = ?,
                    byte_count = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'RUNNING'
                """,
                Timestamp.from(continuationOccurredAt),
                continuationId,
                recordCount,
                byteCount,
                Timestamp.from(now),
                tenant.tenantId(),
                operationId,
                expectedRevision);
        stale(updated, operationId, expectedRevision);
        return find(tenant, operationId).orElseThrow();
    }

    @Override
    public AuditExportOperation complete(
            TenantContext tenant,
            UUID operationId,
            long expectedRevision,
            String artifactReference,
            long recordCount,
            long byteCount,
            String sha256Hex,
            Instant artifactExpiresAt,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_export_operation
                SET state = 'SUCCEEDED',
                    record_count = ?,
                    byte_count = ?,
                    sha256_hex = ?,
                    artifact_reference = ?,
                    artifact_expires_at = ?,
                    failure_code = NULL,
                    completed_at = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'RUNNING'
                """,
                recordCount,
                byteCount,
                sha256Hex,
                artifactReference,
                artifactExpiresAt == null ? null : Timestamp.from(artifactExpiresAt),
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                operationId,
                expectedRevision);
        stale(updated, operationId, expectedRevision);
        return find(tenant, operationId).orElseThrow();
    }

    @Override
    public AuditExportOperation fail(
            TenantContext tenant,
            UUID operationId,
            long expectedRevision,
            String failureCode,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_export_operation
                SET state = 'FAILED',
                    artifact_reference = NULL,
                    artifact_expires_at = NULL,
                    sha256_hex = NULL,
                    failure_code = ?,
                    completed_at = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                  AND state IN ('REQUESTED','RUNNING')
                """,
                failureCode,
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                operationId,
                expectedRevision);
        stale(updated, operationId, expectedRevision);
        return find(tenant, operationId).orElseThrow();
    }

    @Override
    public List<AuditRecord> findSourcePage(
            TenantContext tenant,
            AuditExportOperation.Filter filter,
            Instant occurredFrom,
            Instant occurredUntil,
            Instant snapshotRecordedAt,
            Instant afterOccurredAt,
            UUID afterId,
            int limit) {
        if ((afterOccurredAt == null) != (afterId == null)) {
            throw new IllegalArgumentException("both export continuation values are required");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }

        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, recorded_at, actor_id, action_type,
                       resource_type, resource_id, outcome, correlation_id, causation_id
                FROM audit.audit_record_query
                WHERE tenant_id = ?
                  AND occurred_at >= ?
                  AND occurred_at < ?
                  AND recorded_at <= ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        args.add(Timestamp.from(occurredFrom));
        args.add(Timestamp.from(occurredUntil));
        args.add(Timestamp.from(snapshotRecordedAt));

        if (filter.actorId() != null) {
            sql.append(" AND actor_id = ?");
            args.add(filter.actorId());
        }
        if (filter.actionType() != null) {
            sql.append(" AND action_type = ?");
            args.add(filter.actionType());
        }
        if (filter.resourceType() != null) {
            sql.append(" AND resource_type = ?");
            args.add(filter.resourceType());
        }
        if (filter.resourceId() != null) {
            sql.append(" AND resource_id = ?");
            args.add(filter.resourceId());
        }
        if (filter.outcome() != null) {
            sql.append(" AND outcome = ?");
            args.add(filter.outcome().name());
        }
        if (filter.correlationId() != null) {
            sql.append(" AND correlation_id = ?");
            args.add(filter.correlationId());
        }
        if (afterOccurredAt != null) {
            sql.append(" AND (occurred_at > ? OR (occurred_at = ? AND id > ?))");
            args.add(Timestamp.from(afterOccurredAt));
            args.add(Timestamp.from(afterOccurredAt));
            args.add(afterId);
        }
        sql.append(" ORDER BY occurred_at ASC, id ASC LIMIT ?");
        args.add(limit);

        return jdbc.query(sql.toString(), (rs, rowNum) -> auditRecord(rs), args.toArray());
    }

    private static String selectOperation() {
        return """
                SELECT e.id, e.requested_by_identity_id,
                       e.actor_filter_id, e.action_type_filter, e.resource_type_filter,
                       e.resource_id_filter, e.outcome_filter, e.correlation_id_filter,
                       e.occurred_from, e.occurred_until, e.snapshot_recorded_at,
                       e.schema_version, e.state,
                       e.continuation_occurred_at, e.continuation_id,
                       e.record_count, e.byte_count, e.sha256_hex,
                       e.artifact_reference, e.artifact_expires_at, e.failure_code,
                       e.revision, e.completed_at, e.created_at, e.updated_at
                FROM audit.audit_export_operation e
                """;
    }

    private static AuditExportOperation operation(ResultSet rs) throws SQLException {
        String outcome = rs.getString("outcome_filter");
        return new AuditExportOperation(
                rs.getObject("id", UUID.class),
                rs.getObject("requested_by_identity_id", UUID.class),
                new AuditExportOperation.Filter(
                        rs.getObject("actor_filter_id", UUID.class),
                        rs.getString("action_type_filter"),
                        rs.getString("resource_type_filter"),
                        rs.getObject("resource_id_filter", UUID.class),
                        outcome == null ? null : AuditOutcome.valueOf(outcome),
                        rs.getObject("correlation_id_filter", UUID.class)),
                rs.getTimestamp("occurred_from").toInstant(),
                rs.getTimestamp("occurred_until").toInstant(),
                rs.getTimestamp("snapshot_recorded_at").toInstant(),
                rs.getString("schema_version"),
                AuditExportOperation.State.valueOf(rs.getString("state")),
                instant(rs.getTimestamp("continuation_occurred_at")),
                rs.getObject("continuation_id", UUID.class),
                rs.getLong("record_count"),
                rs.getLong("byte_count"),
                rs.getString("sha256_hex"),
                rs.getString("artifact_reference"),
                instant(rs.getTimestamp("artifact_expires_at")),
                rs.getString("failure_code"),
                rs.getLong("revision"),
                instant(rs.getTimestamp("completed_at")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static AuditRecord auditRecord(ResultSet rs) throws SQLException {
        return new AuditRecord(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getObject("actor_id", UUID.class),
                rs.getString("action_type"),
                rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class),
                AuditOutcome.valueOf(rs.getString("outcome")),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void stale(int updated, UUID operationId, long expectedRevision) {
        if (updated != 1) {
            throw new StaleWriteException("audit-export", operationId, expectedRevision);
        }
    }
}
