package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.IdentityAccessReductionRepository;
import io.wyrmgate.iam.access.domain.IdentityAccessReduction;
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

public final class JdbcIdentityAccessReductionRepository
        implements IdentityAccessReductionRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdentityAccessReductionRepository(
            JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public IdentityAccessReduction startIfAbsent(
            TenantContext tenant,
            IdentityAccessReduction reduction) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(reduction, "reduction");
        jdbc.update("""
                INSERT INTO access.identity_access_reduction (
                    id, tenant_id, identity_id,
                    source_identity_revision, source_lifecycle_state,
                    lifecycle_state, snapshot_at,
                    after_created_at, after_assignment_id,
                    processed_assignment_count, revision,
                    created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    tenant_id, identity_id, source_identity_revision)
                DO NOTHING
                """,
                reduction.id(),
                tenant.tenantId(),
                reduction.identityId(),
                reduction.sourceIdentityRevision(),
                reduction.sourceLifecycleState(),
                reduction.state().name(),
                Timestamp.from(reduction.snapshotAt()),
                timestamp(reduction.afterCreatedAt()),
                reduction.afterAssignmentId(),
                reduction.processedAssignmentCount(),
                reduction.revision(),
                Timestamp.from(reduction.createdAt()),
                Timestamp.from(reduction.updatedAt()),
                timestamp(reduction.completedAt()));
        return findBySource(
                        tenant,
                        reduction.identityId(),
                        reduction.sourceIdentityRevision())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "IdentityAccessReduction could not be reloaded"));
    }

    @Override
    public Optional<IdentityAccessReduction> findById(
            TenantContext tenant,
            UUID reductionId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(reductionId, "reductionId");
        return jdbc.query("""
                SELECT id, identity_id, source_identity_revision,
                       source_lifecycle_state, lifecycle_state,
                       snapshot_at, after_created_at, after_assignment_id,
                       processed_assignment_count, revision,
                       created_at, updated_at, completed_at
                FROM access.identity_access_reduction
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> reduction(rs),
                tenant.tenantId(),
                reductionId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<IdentityAccessReduction> findBySource(
            TenantContext tenant,
            UUID identityId,
            long sourceIdentityRevision) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        if (sourceIdentityRevision < 1) {
            throw new IllegalArgumentException(
                    "sourceIdentityRevision must be positive");
        }
        return jdbc.query("""
                SELECT id, identity_id, source_identity_revision,
                       source_lifecycle_state, lifecycle_state,
                       snapshot_at, after_created_at, after_assignment_id,
                       processed_assignment_count, revision,
                       created_at, updated_at, completed_at
                FROM access.identity_access_reduction
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND source_identity_revision = ?
                """,
                (rs,row) -> reduction(rs),
                tenant.tenantId(),
                identityId,
                sourceIdentityRevision)
                .stream()
                .findFirst();
    }

    @Override
    public IdentityAccessReduction recordPage(
            TenantContext tenant,
            UUID reductionId,
            Instant afterCreatedAt,
            UUID afterAssignmentId,
            long processedDelta,
            boolean completed,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(reductionId, "reductionId");
        Objects.requireNonNull(now, "now");
        if ((afterCreatedAt == null) != (afterAssignmentId == null)) {
            throw new IllegalArgumentException(
                    "checkpoint timestamp and assignment ID must be both present or both absent");
        }
        if (processedDelta < 0) {
            throw new IllegalArgumentException(
                    "processedDelta must not be negative");
        }
        if (expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "expectedRevision must be positive");
        }

        int affected = jdbc.update("""
                UPDATE access.identity_access_reduction
                SET lifecycle_state = ?,
                    after_created_at = ?,
                    after_assignment_id = ?,
                    processed_assignment_count =
                        processed_assignment_count + ?,
                    revision = revision + 1,
                    updated_at = ?,
                    completed_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND lifecycle_state = 'RUNNING'
                  AND revision = ?
                """,
                completed ? "COMPLETED" : "RUNNING",
                timestamp(afterCreatedAt),
                afterAssignmentId,
                processedDelta,
                Timestamp.from(now),
                completed ? Timestamp.from(now) : null,
                tenant.tenantId(),
                reductionId,
                expectedRevision);
        if (affected != 1) {
            IdentityAccessReduction current =
                    findById(tenant, reductionId)
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "IdentityAccessReduction does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "identity-access-reduction",
                        reductionId,
                        expectedRevision);
            }
            if (current.state()
                    == IdentityAccessReduction.State.COMPLETED) {
                return current;
            }
            throw new IllegalStateException(
                    "IdentityAccessReduction page did not update");
        }
        return findById(tenant, reductionId).orElseThrow();
    }

    private static IdentityAccessReduction reduction(
            ResultSet rs) throws SQLException {
        Timestamp after = rs.getTimestamp("after_created_at");
        Timestamp completed = rs.getTimestamp("completed_at");
        return new IdentityAccessReduction(
                rs.getObject("id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                rs.getLong("source_identity_revision"),
                rs.getString("source_lifecycle_state"),
                IdentityAccessReduction.State.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getTimestamp("snapshot_at").toInstant(),
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
