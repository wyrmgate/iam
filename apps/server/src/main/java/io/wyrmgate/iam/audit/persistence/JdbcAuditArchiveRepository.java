package io.wyrmgate.iam.audit.persistence;

import io.wyrmgate.iam.audit.application.AuditArchiveRepository;
import io.wyrmgate.iam.audit.application.AuditRetentionPolicyRepository;
import io.wyrmgate.iam.audit.domain.AuditArchiveSegment;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.audit.domain.AuditRetentionPolicyVersion;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC persistence for ADR-0034 archive segments and immutable retention-policy versions. */
public final class JdbcAuditArchiveRepository
        implements AuditArchiveRepository, AuditRetentionPolicyRepository {

    private final JdbcTemplate jdbc;

    public JdbcAuditArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public AuditRetentionPolicyVersion create(
            TenantContext tenant,
            UUID id,
            long version,
            Duration exportArtifactRetention,
            Duration archiveEligibleAfter,
            Duration minimumOnlineRetention,
            Duration minimumArchiveRetention,
            Instant effectiveFrom,
            Instant createdAt) {
        jdbc.update(
                """
                INSERT INTO audit.audit_retention_policy_version (
                    id, tenant_id, policy_version,
                    export_artifact_retention_seconds,
                    archive_eligible_after_seconds,
                    minimum_online_retention_seconds,
                    minimum_archive_retention_seconds,
                    effective_from, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, policy_version) DO NOTHING
                """,
                id,
                tenant.tenantId(),
                version,
                exportArtifactRetention.toSeconds(),
                archiveEligibleAfter.toSeconds(),
                minimumOnlineRetention.toSeconds(),
                minimumArchiveRetention.toSeconds(),
                Timestamp.from(effectiveFrom),
                Timestamp.from(createdAt));
        return findByVersion(tenant, version).orElseThrow();
    }

    @Override
    public Optional<AuditRetentionPolicyVersion> findByVersion(
            TenantContext tenant,
            long version) {
        return jdbc.query(
                        policySelect() + " WHERE p.tenant_id = ? AND p.policy_version = ?",
                        (rs, rowNum) -> policy(rs),
                        tenant.tenantId(),
                        version)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<AuditRetentionPolicyVersion> findCurrent(
            TenantContext tenant,
            Instant at) {
        return jdbc.query(
                        policySelect() + """
                         WHERE p.tenant_id = ? AND p.effective_from <= ?
                         ORDER BY p.effective_from DESC, p.policy_version DESC
                         LIMIT 1
                        """,
                        (rs, rowNum) -> policy(rs),
                        tenant.tenantId(),
                        Timestamp.from(at))
                .stream()
                .findFirst();
    }

    @Override
    public AuditArchiveSegment createOrFind(
            TenantContext tenant,
            UUID segmentId,
            UUID retentionPolicyVersionId,
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
                    id, tenant_id, retention_policy_version_id,
                    occurred_from, occurred_until, snapshot_recorded_at,
                    schema_version, state, correlation_id, causation_id,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, 1, ?, ?)
                ON CONFLICT (tenant_id, retention_policy_version_id, occurred_from, occurred_until)
                DO NOTHING
                """,
                segmentId,
                tenant.tenantId(),
                retentionPolicyVersionId,
                Timestamp.from(occurredFrom),
                Timestamp.from(occurredUntil),
                Timestamp.from(snapshotRecordedAt),
                schemaVersion,
                correlationId,
                causationId,
                Timestamp.from(now),
                Timestamp.from(now));
        return jdbc.query(
                        selectSegment() + """
                         WHERE s.tenant_id = ?
                           AND s.retention_policy_version_id = ?
                           AND s.occurred_from = ?
                           AND s.occurred_until = ?
                        """,
                        (rs, rowNum) -> segment(rs),
                        tenant.tenantId(),
                        retentionPolicyVersionId,
                        Timestamp.from(occurredFrom),
                        Timestamp.from(occurredUntil))
                .stream()
                .findFirst()
                .orElseThrow();
    }

    @Override
    public Optional<AuditArchiveSegment> find(
            TenantContext tenant,
            UUID segmentId) {
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
                    verified_at = NULL,
                    minimum_retain_until = NULL,
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
            Instant verifiedAt,
            Instant minimumRetainUntil,
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
                    verified_at = ?,
                    minimum_retain_until = ?,
                    completed_at = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'RUNNING'
                """,
                recordCount,
                byteCount,
                sha256Hex,
                artifactReference,
                Timestamp.from(verifiedAt),
                Timestamp.from(minimumRetainUntil),
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
                    verified_at = NULL,
                    minimum_retain_until = NULL,
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

        String continuation = afterOccurredAt == null
                ? ""
                : " AND (occurred_at > ? OR (occurred_at = ? AND id > ?))";
        Object[] args = afterOccurredAt == null
                ? new Object[] {
                    tenant.tenantId(),
                    Timestamp.from(occurredFrom),
                    Timestamp.from(occurredUntil),
                    Timestamp.from(snapshotRecordedAt),
                    limit
                }
                : new Object[] {
                    tenant.tenantId(),
                    Timestamp.from(occurredFrom),
                    Timestamp.from(occurredUntil),
                    Timestamp.from(snapshotRecordedAt),
                    Timestamp.from(afterOccurredAt),
                    Timestamp.from(afterOccurredAt),
                    afterId,
                    limit
                };
        return jdbc.query(
                """
                SELECT id, occurred_at, recorded_at, actor_id, action_type,
                       resource_type, resource_id, outcome, correlation_id, causation_id
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND occurred_at >= ?
                  AND occurred_at < ?
                  AND recorded_at <= ?
                """ + continuation + " ORDER BY occurred_at ASC, id ASC LIMIT ?",
                (rs, rowNum) -> auditRecord(rs),
                args);
    }

    private static String policySelect() {
        return """
                SELECT p.id, p.policy_version,
                       p.export_artifact_retention_seconds,
                       p.archive_eligible_after_seconds,
                       p.minimum_online_retention_seconds,
                       p.minimum_archive_retention_seconds,
                       p.effective_from, p.created_at
                FROM audit.audit_retention_policy_version p
                """;
    }

    private static AuditRetentionPolicyVersion policy(ResultSet rs) throws SQLException {
        return new AuditRetentionPolicyVersion(
                rs.getObject("id", UUID.class),
                rs.getLong("policy_version"),
                Duration.ofSeconds(rs.getLong("export_artifact_retention_seconds")),
                Duration.ofSeconds(rs.getLong("archive_eligible_after_seconds")),
                Duration.ofSeconds(rs.getLong("minimum_online_retention_seconds")),
                Duration.ofSeconds(rs.getLong("minimum_archive_retention_seconds")),
                rs.getTimestamp("effective_from").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String selectSegment() {
        return """
                SELECT s.id, s.retention_policy_version_id,
                       s.occurred_from, s.occurred_until, s.snapshot_recorded_at,
                       s.schema_version, s.state,
                       s.continuation_occurred_at, s.continuation_id,
                       s.record_count, s.byte_count, s.sha256_hex,
                       s.artifact_reference, s.failure_code,
                       s.correlation_id, s.causation_id, s.revision,
                       s.verified_at, s.minimum_retain_until, s.completed_at,
                       s.created_at, s.updated_at
                FROM audit.audit_archive_segment s
                """;
    }

    private static AuditArchiveSegment segment(ResultSet rs) throws SQLException {
        return new AuditArchiveSegment(
                rs.getObject("id", UUID.class),
                rs.getObject("retention_policy_version_id", UUID.class),
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
                instant(rs.getTimestamp("verified_at")),
                instant(rs.getTimestamp("minimum_retain_until")),
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
            throw new StaleWriteException("audit-archive", segmentId, expectedRevision);
        }
    }
}
