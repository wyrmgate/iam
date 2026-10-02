package io.wyrmgate.iam.audit.persistence;

import io.wyrmgate.iam.audit.application.AuditEvidenceLifecycleRepository;
import io.wyrmgate.iam.audit.domain.AuditArchiveSegment;
import io.wyrmgate.iam.audit.domain.AuditLegalHold;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditPurgeOperation;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.audit.domain.AuditSelection;
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

/** JDBC persistence for ADR-0035 legal hold, purge and archived-query projection state. */
public final class JdbcAuditEvidenceLifecycleRepository implements AuditEvidenceLifecycleRepository {

    private final JdbcTemplate jdbc;

    public JdbcAuditEvidenceLifecycleRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void lockLifecycle(TenantContext tenant) {
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Long.class,
                tenant.tenantId().toString());
    }

    @Override
    public AuditLegalHold createHold(
            TenantContext tenant,
            UUID holdId,
            AuditSelection selection,
            String reasonCode,
            String caseReference,
            UUID createdByIdentityId,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        lockLifecycle(tenant);
        jdbc.update(
                """
                INSERT INTO audit.audit_legal_hold (
                    id, tenant_id, occurred_from, occurred_until,
                    actor_filter_id, action_type_filter, resource_type_filter,
                    resource_id_filter, outcome_filter, correlation_id_filter,
                    reason_code, case_reference, state, created_by_identity_id,
                    correlation_id, causation_id, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 1, ?, ?)
                """,
                holdId,
                tenant.tenantId(),
                Timestamp.from(selection.occurredFrom()),
                Timestamp.from(selection.occurredUntil()),
                selection.actorId(),
                selection.actionType(),
                selection.resourceType(),
                selection.resourceId(),
                selection.outcome() == null ? null : selection.outcome().name(),
                selection.correlationId(),
                reasonCode,
                caseReference,
                createdByIdentityId,
                correlationId,
                causationId,
                Timestamp.from(now),
                Timestamp.from(now));
        return findHold(tenant, holdId).orElseThrow();
    }

    @Override
    public Optional<AuditLegalHold> findHold(TenantContext tenant, UUID holdId) {
        return jdbc.query(
                        selectHold() + " WHERE h.tenant_id = ? AND h.id = ?",
                        (rs, rowNum) -> hold(rs),
                        tenant.tenantId(),
                        holdId)
                .stream()
                .findFirst();
    }

    @Override
    public AuditLegalHold releaseHold(
            TenantContext tenant,
            UUID holdId,
            long expectedRevision,
            UUID releasedByIdentityId,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_legal_hold
                SET state = 'RELEASED',
                    released_by_identity_id = ?,
                    released_at = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'ACTIVE'
                """,
                releasedByIdentityId,
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                holdId,
                expectedRevision);
        stale(updated, "audit-legal-hold", holdId, expectedRevision);
        return findHold(tenant, holdId).orElseThrow();
    }

    @Override
    public boolean hasMatchingActiveHold(
            TenantContext tenant,
            AuditSelection selection,
            Instant snapshotRecordedAt) {
        StringBuilder sql = new StringBuilder("""
                SELECT EXISTS (
                    SELECT 1
                    FROM audit.audit_record r
                    JOIN audit.audit_legal_hold h
                      ON h.tenant_id = r.tenant_id
                     AND h.state = 'ACTIVE'
                     AND r.occurred_at >= h.occurred_from
                     AND r.occurred_at < h.occurred_until
                     AND (h.actor_filter_id IS NULL OR r.actor_id = h.actor_filter_id)
                     AND (h.action_type_filter IS NULL OR r.action_type = h.action_type_filter)
                     AND (h.resource_type_filter IS NULL OR r.resource_type = h.resource_type_filter)
                     AND (h.resource_id_filter IS NULL OR r.resource_id = h.resource_id_filter)
                     AND (h.outcome_filter IS NULL OR r.outcome = h.outcome_filter)
                     AND (h.correlation_id_filter IS NULL OR r.correlation_id = h.correlation_id_filter)
                    WHERE r.tenant_id = ?
                      AND r.occurred_at >= ?
                      AND r.occurred_at < ?
                      AND r.recorded_at <= ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        args.add(Timestamp.from(selection.occurredFrom()));
        args.add(Timestamp.from(selection.occurredUntil()));
        args.add(Timestamp.from(snapshotRecordedAt));
        appendRecordFilters(sql, args, selection, "r.");
        sql.append(" LIMIT 1)");
        return Boolean.TRUE.equals(jdbc.queryForObject(sql.toString(), Boolean.class, args.toArray()));
    }

    @Override
    public AuditPurgeOperation createPurge(
            TenantContext tenant,
            UUID purgeId,
            UUID retentionPolicyVersionId,
            UUID archiveSegmentId,
            UUID requestedByIdentityId,
            AuditSelection selection,
            Instant snapshotRecordedAt,
            String reasonCode,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        jdbc.update(
                """
                INSERT INTO audit.audit_purge_operation (
                    id, tenant_id, retention_policy_version_id, archive_segment_id,
                    requested_by_identity_id, occurred_from, occurred_until,
                    snapshot_recorded_at, actor_filter_id, action_type_filter,
                    resource_type_filter, resource_id_filter, outcome_filter,
                    correlation_id_filter, reason_code, state,
                    correlation_id, causation_id, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, 1, ?, ?)
                """,
                purgeId,
                tenant.tenantId(),
                retentionPolicyVersionId,
                archiveSegmentId,
                requestedByIdentityId,
                Timestamp.from(selection.occurredFrom()),
                Timestamp.from(selection.occurredUntil()),
                Timestamp.from(snapshotRecordedAt),
                selection.actorId(),
                selection.actionType(),
                selection.resourceType(),
                selection.resourceId(),
                selection.outcome() == null ? null : selection.outcome().name(),
                selection.correlationId(),
                reasonCode,
                correlationId,
                causationId,
                Timestamp.from(now),
                Timestamp.from(now));
        return findPurge(tenant, purgeId).orElseThrow();
    }

    @Override
    public Optional<AuditPurgeOperation> findPurge(TenantContext tenant, UUID purgeId) {
        return jdbc.query(
                        selectPurge() + " WHERE p.tenant_id = ? AND p.id = ?",
                        (rs, rowNum) -> purge(rs),
                        tenant.tenantId(),
                        purgeId)
                .stream()
                .findFirst();
    }

    @Override
    public AuditPurgeOperation approvePurge(
            TenantContext tenant,
            UUID purgeId,
            long expectedRevision,
            UUID approvedByIdentityId,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_purge_operation
                SET state = 'APPROVED',
                    approved_by_identity_id = ?,
                    approved_at = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'REQUESTED'
                  AND requested_by_identity_id <> ?
                """,
                approvedByIdentityId,
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                purgeId,
                expectedRevision,
                approvedByIdentityId);
        stale(updated, "audit-purge", purgeId, expectedRevision);
        return findPurge(tenant, purgeId).orElseThrow();
    }

    @Override
    public AuditPurgeOperation blockApprovedPurge(
            TenantContext tenant,
            UUID purgeId,
            long expectedRevision,
            String failureCode,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_purge_operation
                SET state = 'BLOCKED',
                    failure_code = ?,
                    completed_at = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'APPROVED'
                """,
                failureCode,
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                purgeId,
                expectedRevision);
        stale(updated, "audit-purge", purgeId, expectedRevision);
        return findPurge(tenant, purgeId).orElseThrow();
    }

    @Override
    public AuditPurgeOperation beginPurge(
            TenantContext tenant,
            UUID purgeId,
            long expectedRevision,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE audit.audit_purge_operation
                SET state = 'RUNNING',
                    failure_code = NULL,
                    deleted_record_count = 0,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'APPROVED'
                """,
                Timestamp.from(now),
                tenant.tenantId(),
                purgeId,
                expectedRevision);
        stale(updated, "audit-purge", purgeId, expectedRevision);
        return findPurge(tenant, purgeId).orElseThrow();
    }

    @Override
    public AuditPurgeOperation finishPurge(
            TenantContext tenant,
            UUID purgeId,
            long expectedRevision,
            AuditPurgeOperation.State terminalState,
            long deletedRecordCount,
            String failureCode,
            Instant now) {
        if (terminalState != AuditPurgeOperation.State.SUCCEEDED
                && terminalState != AuditPurgeOperation.State.FAILED
                && terminalState != AuditPurgeOperation.State.BLOCKED) {
            throw new IllegalArgumentException("terminalState must be SUCCEEDED, FAILED, or BLOCKED");
        }
        int updated = jdbc.update(
                """
                UPDATE audit.audit_purge_operation
                SET state = ?,
                    deleted_record_count = ?,
                    failure_code = ?,
                    completed_at = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND state = 'RUNNING'
                """,
                terminalState.name(),
                deletedRecordCount,
                failureCode,
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                purgeId,
                expectedRevision);
        stale(updated, "audit-purge", purgeId, expectedRevision);
        return findPurge(tenant, purgeId).orElseThrow();
    }

    @Override
    public long executeFencedDelete(
            TenantContext tenant,
            AuditPurgeOperation operation) {
        jdbc.queryForObject(
                "SELECT set_config('wyrmgate.audit_purge_operation_id', ?, true)",
                String.class,
                operation.id().toString());

        AuditSelection selection = operation.selection();
        StringBuilder sql = new StringBuilder("""
                DELETE FROM audit.audit_record
                WHERE tenant_id = ?
                  AND occurred_at >= ?
                  AND occurred_at < ?
                  AND recorded_at <= ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant.tenantId());
        args.add(Timestamp.from(selection.occurredFrom()));
        args.add(Timestamp.from(selection.occurredUntil()));
        args.add(Timestamp.from(operation.snapshotRecordedAt()));
        appendRecordFilters(sql, args, selection, "");
        return jdbc.update(sql.toString(), args.toArray());
    }

    @Override
    public void indexArchivedRecords(
            TenantContext tenant,
            AuditArchiveSegment segment,
            List<AuditRecord> records,
            Instant archivedAt) {
        for (AuditRecord record : records) {
            jdbc.update(
                    """
                    INSERT INTO audit.audit_archived_record_index (
                        tenant_id, record_id, archive_segment_id,
                        occurred_at, recorded_at, actor_id, action_type,
                        resource_type, resource_id, outcome,
                        correlation_id, causation_id, archived_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, record_id) DO NOTHING
                    """,
                    tenant.tenantId(),
                    record.id(),
                    segment.id(),
                    Timestamp.from(record.occurredAt()),
                    Timestamp.from(record.recordedAt()),
                    record.actorId(),
                    record.actionType(),
                    record.resourceType(),
                    record.resourceId(),
                    record.outcome().name(),
                    record.correlationId(),
                    record.causationId(),
                    Timestamp.from(archivedAt));
        }
    }

    private static String selectHold() {
        return """
                SELECT h.id, h.occurred_from, h.occurred_until,
                       h.actor_filter_id, h.action_type_filter, h.resource_type_filter,
                       h.resource_id_filter, h.outcome_filter, h.correlation_id_filter,
                       h.reason_code, h.case_reference, h.state,
                       h.created_by_identity_id, h.released_by_identity_id,
                       h.correlation_id, h.causation_id, h.revision,
                       h.created_at, h.released_at, h.updated_at
                FROM audit.audit_legal_hold h
                """;
    }

    private static AuditLegalHold hold(ResultSet rs) throws SQLException {
        return new AuditLegalHold(
                rs.getObject("id", UUID.class),
                selection(rs),
                rs.getString("reason_code"),
                rs.getString("case_reference"),
                AuditLegalHold.State.valueOf(rs.getString("state")),
                rs.getObject("created_by_identity_id", UUID.class),
                rs.getObject("released_by_identity_id", UUID.class),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("released_at")),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String selectPurge() {
        return """
                SELECT p.id, p.retention_policy_version_id, p.archive_segment_id,
                       p.requested_by_identity_id, p.approved_by_identity_id,
                       p.occurred_from, p.occurred_until, p.snapshot_recorded_at,
                       p.actor_filter_id, p.action_type_filter, p.resource_type_filter,
                       p.resource_id_filter, p.outcome_filter, p.correlation_id_filter,
                       p.reason_code, p.state, p.deleted_record_count, p.failure_code,
                       p.correlation_id, p.causation_id, p.revision,
                       p.approved_at, p.completed_at, p.created_at, p.updated_at
                FROM audit.audit_purge_operation p
                """;
    }

    private static AuditPurgeOperation purge(ResultSet rs) throws SQLException {
        return new AuditPurgeOperation(
                rs.getObject("id", UUID.class),
                rs.getObject("retention_policy_version_id", UUID.class),
                rs.getObject("archive_segment_id", UUID.class),
                rs.getObject("requested_by_identity_id", UUID.class),
                rs.getObject("approved_by_identity_id", UUID.class),
                selection(rs),
                rs.getTimestamp("snapshot_recorded_at").toInstant(),
                rs.getString("reason_code"),
                AuditPurgeOperation.State.valueOf(rs.getString("state")),
                rs.getLong("deleted_record_count"),
                rs.getString("failure_code"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getLong("revision"),
                instant(rs.getTimestamp("approved_at")),
                instant(rs.getTimestamp("completed_at")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static AuditSelection selection(ResultSet rs) throws SQLException {
        String outcome = rs.getString("outcome_filter");
        return new AuditSelection(
                rs.getTimestamp("occurred_from").toInstant(),
                rs.getTimestamp("occurred_until").toInstant(),
                rs.getObject("actor_filter_id", UUID.class),
                rs.getString("action_type_filter"),
                rs.getString("resource_type_filter"),
                rs.getObject("resource_id_filter", UUID.class),
                outcome == null ? null : AuditOutcome.valueOf(outcome),
                rs.getObject("correlation_id_filter", UUID.class));
    }

    private static void appendRecordFilters(
            StringBuilder sql,
            List<Object> args,
            AuditSelection selection,
            String prefix) {
        if (selection.actorId() != null) {
            sql.append(" AND ").append(prefix).append("actor_id = ?");
            args.add(selection.actorId());
        }
        if (selection.actionType() != null) {
            sql.append(" AND ").append(prefix).append("action_type = ?");
            args.add(selection.actionType());
        }
        if (selection.resourceType() != null) {
            sql.append(" AND ").append(prefix).append("resource_type = ?");
            args.add(selection.resourceType());
        }
        if (selection.resourceId() != null) {
            sql.append(" AND ").append(prefix).append("resource_id = ?");
            args.add(selection.resourceId());
        }
        if (selection.outcome() != null) {
            sql.append(" AND ").append(prefix).append("outcome = ?");
            args.add(selection.outcome().name());
        }
        if (selection.correlationId() != null) {
            sql.append(" AND ").append(prefix).append("correlation_id = ?");
            args.add(selection.correlationId());
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void stale(int updated, String resource, UUID id, long revision) {
        if (updated != 1) throw new StaleWriteException(resource, id, revision);
    }
}
