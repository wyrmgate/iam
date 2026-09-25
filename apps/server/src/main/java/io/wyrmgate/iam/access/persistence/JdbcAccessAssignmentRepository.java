package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessAssignmentCommandException;
import io.wyrmgate.iam.access.application.AccessAssignmentRepository;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAccessAssignmentRepository
        implements AccessAssignmentRepository {

    private final JdbcTemplate jdbc;

    public JdbcAccessAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantContext tenant, AccessAssignment assignment) {
        jdbc.update("""
                INSERT INTO access.access_assignment (
                    id, tenant_id, identity_id, target_kind, role_id, entitlement_id,
                    principal_constraint_kind, specific_principal_id,
                    provenance_kind, provenance_ref_id, lifecycle_state,
                    valid_from, valid_until, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                assignment.id(),
                tenant.tenantId(),
                assignment.identityId(),
                assignment.targetKind().name(),
                assignment.roleId(),
                assignment.entitlementId(),
                assignment.principalConstraintKind().name(),
                assignment.specificPrincipalId(),
                assignment.provenanceKind().name(),
                assignment.provenanceRefId(),
                assignment.lifecycleState().name(),
                timestamp(assignment.validFrom()),
                timestamp(assignment.validUntil()),
                assignment.revision(),
                Timestamp.from(assignment.createdAt()),
                Timestamp.from(assignment.updatedAt()));
    }

    @Override
    public Optional<AccessAssignment> findById(
            TenantContext tenant, UUID assignmentId) {
        return jdbc.query("""
                SELECT id, identity_id, target_kind, role_id, entitlement_id,
                       principal_constraint_kind, specific_principal_id,
                       provenance_kind, provenance_ref_id, lifecycle_state,
                       valid_from, valid_until, revision, created_at, updated_at
                FROM access.access_assignment
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> assignment(rs),
                tenant.tenantId(),
                assignmentId)
                .stream()
                .findFirst();
    }

    @Override
    public List<AccessAssignment> findByRoleId(
            TenantContext tenant, UUID roleId) {
        return jdbc.query("""
                SELECT id, identity_id, target_kind, role_id, entitlement_id,
                       principal_constraint_kind, specific_principal_id,
                       provenance_kind, provenance_ref_id, lifecycle_state,
                       valid_from, valid_until, revision, created_at, updated_at
                FROM access.access_assignment
                WHERE tenant_id = ? AND target_kind = 'ROLE' AND role_id = ?
                ORDER BY id
                """,
                (rs,row) -> assignment(rs),
                tenant.tenantId(),
                roleId);
    }

    @Override
    public AccessAssignment terminate(
            TenantContext tenant,
            UUID assignmentId,
            AccessAssignment.LifecycleState terminalState,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE access.access_assignment
                SET lifecycle_state = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                """,
                terminalState.name(),
                Timestamp.from(now),
                tenant.tenantId(),
                assignmentId,
                expectedRevision);
        if (affected != 1) {
            AccessAssignment current = findById(tenant, assignmentId)
                    .orElseThrow(() -> new AccessAssignmentCommandException(
                            "access_assignment_not_found",
                            "The requested AccessAssignment was not found."));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "access-assignment", assignmentId, expectedRevision);
            }
            throw new IllegalStateException(
                    "AccessAssignment termination did not update");
        }
        return findById(tenant, assignmentId).orElseThrow();
    }

    private static AccessAssignment assignment(ResultSet rs) throws SQLException {
        Timestamp validFrom = rs.getTimestamp("valid_from");
        Timestamp validUntil = rs.getTimestamp("valid_until");
        return new AccessAssignment(
                rs.getObject("id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                AccessAssignment.TargetKind.valueOf(rs.getString("target_kind")),
                rs.getObject("role_id", UUID.class),
                rs.getObject("entitlement_id", UUID.class),
                AccessAssignment.PrincipalConstraintKind.valueOf(
                        rs.getString("principal_constraint_kind")),
                rs.getObject("specific_principal_id", UUID.class),
                AccessAssignment.ProvenanceKind.valueOf(
                        rs.getString("provenance_kind")),
                rs.getObject("provenance_ref_id", UUID.class),
                AccessAssignment.LifecycleState.valueOf(
                        rs.getString("lifecycle_state")),
                validFrom == null ? null : validFrom.toInstant(),
                validUntil == null ? null : validUntil.toInstant(),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
