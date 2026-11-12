package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessTargetReductionRepository;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.AccessTargetReduction;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAccessTargetReductionRepository
        implements AccessTargetReductionRepository {

    private final JdbcTemplate jdbc;

    public JdbcAccessTargetReductionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public AccessTargetReduction startIfAbsent(
            TenantContext tenant,
            AccessTargetReduction reduction) {
        jdbc.update(
                """
                INSERT INTO access.access_target_reduction (
                    id, tenant_id, target_kind, target_id,
                    source_cause_kind, source_cause_id, source_cause_revision,
                    lifecycle_state,
                    after_created_at, after_assignment_id,
                    processed_assignment_count, revision,
                    created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    tenant_id, target_kind, target_id,
                    source_cause_kind, source_cause_id, source_cause_revision)
                DO NOTHING
                """,
                reduction.id(),
                tenant.tenantId(),
                reduction.targetKind().name(),
                reduction.targetId(),
                reduction.sourceCauseKind().name(),
                reduction.sourceCauseId(),
                reduction.sourceCauseRevision(),
                reduction.state().name(),
                timestamp(reduction.afterCreatedAt()),
                reduction.afterAssignmentId(),
                reduction.processedAssignmentCount(),
                reduction.revision(),
                Timestamp.from(reduction.createdAt()),
                Timestamp.from(reduction.updatedAt()),
                timestamp(reduction.completedAt()));
        return findBySource(tenant, reduction)
                .orElseThrow(() -> new IllegalStateException(
                        "AccessTargetReduction could not be reloaded"));
    }

    @Override
    public Optional<AccessTargetReduction> findById(
            TenantContext tenant, UUID reductionId) {
        return jdbc.query(
                        baseSelect() + " WHERE tenant_id = ? AND id = ?",
                        (rs, rowNum) -> reduction(rs),
                        tenant.tenantId(),
                        reductionId)
                .stream()
                .findFirst();
    }

    @Override
    public AccessTargetReduction recordPage(
            TenantContext tenant,
            UUID reductionId,
            Instant afterCreatedAt,
            UUID afterAssignmentId,
            long processedDelta,
            boolean completed,
            long expectedRevision,
            Instant now) {
        if ((afterCreatedAt == null) != (afterAssignmentId == null)) {
            throw new IllegalArgumentException("checkpoint fields must be both present or both absent");
        }
        if (processedDelta < 0 || expectedRevision < 1) {
            throw new IllegalArgumentException("invalid reduction progress");
        }
        int affected = jdbc.update(
                """
                UPDATE access.access_target_reduction
                SET after_created_at = ?,
                    after_assignment_id = ?,
                    processed_assignment_count = processed_assignment_count + ?,
                    revision = revision + 1,
                    lifecycle_state = ?,
                    updated_at = ?,
                    completed_at = ?
                WHERE tenant_id = ? AND id = ?
                  AND revision = ? AND lifecycle_state = 'RUNNING'
                """,
                timestamp(afterCreatedAt),
                afterAssignmentId,
                processedDelta,
                completed ? "COMPLETED" : "RUNNING",
                Timestamp.from(now),
                completed ? Timestamp.from(now) : null,
                tenant.tenantId(),
                reductionId,
                expectedRevision);
        if (affected != 1) {
            AccessTargetReduction current = findById(tenant, reductionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "AccessTargetReduction does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "access-target-reduction", reductionId, expectedRevision);
            }
            if (current.state() == AccessTargetReduction.State.COMPLETED) {
                return current;
            }
            throw new IllegalStateException("AccessTargetReduction page did not update");
        }
        return findById(tenant, reductionId).orElseThrow();
    }

    private Optional<AccessTargetReduction> findBySource(
            TenantContext tenant,
            AccessTargetReduction source) {
        return jdbc.query(
                        baseSelect()
                                + """
                                  WHERE tenant_id = ?
                                    AND target_kind = ?
                                    AND target_id = ?
                                    AND source_cause_kind = ?
                                    AND source_cause_id = ?
                                    AND source_cause_revision = ?
                                """,
                        (rs, rowNum) -> reduction(rs),
                        tenant.tenantId(),
                        source.targetKind().name(),
                        source.targetId(),
                        source.sourceCauseKind().name(),
                        source.sourceCauseId(),
                        source.sourceCauseRevision())
                .stream()
                .findFirst();
    }

    private static String baseSelect() {
        return """
                SELECT id, target_kind, target_id,
                       source_cause_kind, source_cause_id, source_cause_revision,
                       lifecycle_state,
                       after_created_at, after_assignment_id,
                       processed_assignment_count, revision,
                       created_at, updated_at, completed_at
                FROM access.access_target_reduction
                """;
    }

    private static AccessTargetReduction reduction(ResultSet rs) throws SQLException {
        Timestamp after = rs.getTimestamp("after_created_at");
        Timestamp completed = rs.getTimestamp("completed_at");
        return new AccessTargetReduction(
                rs.getObject("id", UUID.class),
                AccessAssignment.TargetKind.valueOf(rs.getString("target_kind")),
                rs.getObject("target_id", UUID.class),
                AccessTargetReduction.CauseKind.valueOf(rs.getString("source_cause_kind")),
                rs.getObject("source_cause_id", UUID.class),
                rs.getLong("source_cause_revision"),
                AccessTargetReduction.State.valueOf(rs.getString("lifecycle_state")),
                after == null ? null : after.toInstant(),
                rs.getObject("after_assignment_id", UUID.class),
                rs.getLong("processed_assignment_count"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
