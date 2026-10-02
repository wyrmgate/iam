package io.wyrmgate.iam.audit.persistence;

import io.wyrmgate.iam.audit.application.AuditArchiveRepository;
import io.wyrmgate.iam.audit.domain.AuditArchiveSegment;
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

public final class JdbcAuditArchiveRepository implements AuditArchiveRepository {

    private final JdbcTemplate jdbc;

    public JdbcAuditArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public AuditArchiveSegment create(
            TenantContext tenant,
            UUID segmentId,
            long retentionPolicyVersion,
            Instant occurredFrom,
            Instant occurredUntil,
            Instant snapshotRecordedAt,
            String schemaVersion,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        jdbc.update(
                """
                INSERT INTO audit.audit_archive_segment (
                    id, tenant_id, retention_policy_version,
                    occurred_from, occurred_until, snapshot_recorded_at,
                    schema_version, state, correlation_id, causation_id,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, 1, ?, ?)
                """,
                segmentId,
                tenant.tenantId(),
                retentionPolicyVersion,
                Timestamp.from(occurredFrom),
                Timestamp.from(occurredUntil),
                Timestamp.from(snapshotRecordedAt),
                schemaVersion,
                correlationId,
                causationId,
                Timestamp.from(now),
                Timestamp.from(now));
        return find(tenant, segmentId).orElseThrow();
    }

    @Override
    public Optional<AuditArchiveSegment> find(TenantContext tenant, UUID segmentId) {
        return jdbc.query(
                        selectSegment() + " WHERE s.tenant_id = ? AND s.id = ?",
                        (rs, rowNum) -> segment(rs),
                        tenant.tenantId(),
                        segmentId)
                .stream()
                .findFirst();
    }

    @Override
    public AuditArchiveSegment beginRun(
            TenantContext tenant,
            UUID segmentId,
            long expectedRevision,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_archive_segment
                SET state = 'RUNNING',
                    continuation_occurred_at = NULL,
                    continuation_id = NULL,
                    record_count = 0,
                    byte_count = 0,
                    sha256_hex = NULL,
                    artifact_reference = NULL,
                    failure_code = NULL,
                    completed_at = NULL,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                  AND state IN ('REQUESTED','RUNNING')
                """,
                Timestamp.from(now),
                tenant.tenantId(),
                segmentId,
                expectedRevision);
        stale(updated, segmentId, expectedRevision);
        return find(tenant, segmentId).orElseThrow();
    }

    @Override
    public AuditArchiveSegment checkpoint(
            TenantContext tenant,
            UUID segmentId,
            long expectedRevision,
            Instant continuationOccurredAt,
            UUID continuationId,
            long recordCount,
            long byteCount,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_archive_segment
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
                segmentId,
                expectedRevision);
        stale(updated, segmentId, expectedRevision);
        return find(tenant, segmentId).orElseThrow();
    }

    @Override
    public AuditArchiveSegment complete(
            TenantContext tenant,
            UUID segmentId,
            long expectedRevision,
            String artifactReference,
            long recordCount,
            long byteCount,
            String sha256Hex,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_archive_segment
                SET state = 'SUCCEEDED',
                    record_count = ?,
                    byte_count = ?,
                    sha256_hex = ?,
                    artifact_reference = ?,
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
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                segmentId,
                expectedRevision);
        stale(updated, segmentId, expectedRevision);
        return find(tenant, segmentId).orElseThrow();
    }

    @Override
    public AuditArchiveSegment fail(
            TenantContext tenant,
            UUID segmentId,
            long expectedRevision,
            String failureCode,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_archive_segment
                SET state = 'FAILED',
                    artifact_reference = NULL,
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
                segmentId,
                expectedRevision);
        stale(updated, segmentId, expectedRevision);
        return find(tenant, segmentId).orElseThrow();
    }

    @Override
    public List<AuditRecord> findSourcePage(
            TenantContext tenant,
            Instant occurredFrom,
            Instant occurredUntil,
            Instant snapshotRecordedAt,
            Instant afterOccurredAt,
            UUID afterId,
            int limit) {
        if ((afterOccurredAt == null) != (afterId == null)) {
            throw new IllegalArgumentException("both archive continuation values are required");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }

        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, recorded_at, actor_id, action_type,
                       resource_type, resource_id, outcome, correlation_id, causation_id
                FROM audit.audit_record
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

    private static String selectSegment() {
        return """
                SELECT s.id, s.retention_policy_version,
                       s.occurred_from, s.occurred_until, s.snapshot_recorded_at,
                       s.schema_version, s.state,
                       s.continuation_occurred_at, s.continuation_id,
                       s.record_count, s.byte_count, s.sha256_hex,
                       s.artifact_reference, s.failure_code,
                       s.correlation_id, s.causation_id,
                       s.revision, s.completed_at, s.created_at, s.updated_at
                FROM audit.audit_archive_segment s
                """;
    }

    private static AuditArchiveSegment segment(ResultSet rs) throws SQLException {
        return new AuditArchiveSegment(
                rs.getObject("id", UUID.class),
                rs.getLong("retention_policy_version"),
                rs.getTimestamp("occurred_from").toInstant(),
                rs.getTimestamp("occurred_until").toInstant(),
                rs.getTimestamp("snapshot_recorded_at").toInstant(),
                rs.getString("schema_version"),
                AuditArchiveSegment.State.valueOf(rs.getString("state")),
                instant(rs.getTimestamp("continuation_occurred_at")),
                rs.getObject("continuation_id", UUID.class),
                rs.getLong("record_count"),
                rs.getLong("byte_count"),
                rs.getString("sha256_hex"),
                rs.getString("artifact_reference"),
                rs.getString("failure_code"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
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

    private static void stale(int updated, UUID segmentId, long expectedRevision) {
        if (updated != 1) {
            throw new StaleWriteException("audit-archive-segment", segmentId, expectedRevision);
        }
    }
}
