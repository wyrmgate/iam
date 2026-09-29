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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAccessAssignmentRepository
        implements AccessAssignmentRepository {

    private final JdbcTemplate jdbc;

    public JdbcAccessAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantContext tenant, AccessAssignment assignment) {
        try {
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
        } catch (DataIntegrityViolationException conflict) {
            if (assignment.provenanceKind()
                            == AccessAssignment.ProvenanceKind.REQUEST_ITEM
                    && causedByConstraint(
                            conflict,
                            "access_assignment_request_item_provenance_uq")) {
                throw new io.wyrmgate.iam.access.application
                        .AccessAssignmentProvenanceConflictException();
            }
            throw conflict;
        }
    }

    private static boolean causedByConstraint(
            Throwable error,
            String constraintName) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.contains(constraintName)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
    public Optional<AccessAssignment> findByProvenance(
            TenantContext tenant,
            AccessAssignment.ProvenanceKind provenanceKind,
            UUID provenanceRefId) {
        return jdbc.query("""
                SELECT id, identity_id, target_kind, role_id, entitlement_id,
                       principal_constraint_kind, specific_principal_id,
                       provenance_kind, provenance_ref_id, lifecycle_state,
                       valid_from, valid_until, revision, created_at, updated_at
                FROM access.access_assignment
                WHERE tenant_id = ?
                  AND provenance_kind = ?
                  AND provenance_ref_id = ?
                """,
                (rs,row) -> assignment(rs),
                tenant.tenantId(),
                provenanceKind.name(),
                provenanceRefId)
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
    public List<AccessAssignment> findPage(
            TenantContext tenant,
            Instant afterCreatedAt,
            UUID afterId,
            int limit) {
        if (afterCreatedAt == null || afterId == null) {
            return jdbc.query("""
                    SELECT id, identity_id, target_kind, role_id, entitlement_id,
                           principal_constraint_kind, specific_principal_id,
                           provenance_kind, provenance_ref_id, lifecycle_state,
                           valid_from, valid_until, revision, created_at, updated_at
                    FROM access.access_assignment
                    WHERE tenant_id = ?
                    ORDER BY created_at, id
                    LIMIT ?
                    """,
                    (rs,row) -> assignment(rs),
                    tenant.tenantId(),
                    limit);
        }
        return jdbc.query("""
                SELECT id, identity_id, target_kind, role_id, entitlement_id,
                       principal_constraint_kind, specific_principal_id,
                       provenance_kind, provenance_ref_id, lifecycle_state,
                       valid_from, valid_until, revision, created_at, updated_at
                FROM access.access_assignment
                WHERE tenant_id = ?
                  AND (created_at, id) > (?, ?)
                ORDER BY created_at, id
                LIMIT ?
                """,
                (rs,row) -> assignment(rs),
                tenant.tenantId(),
                Timestamp.from(afterCreatedAt),
                afterId,
                limit);
    }

    @Override
    public List<AccessAssignment> findReviewPage(
            TenantContext tenant,
            UUID identityId,
            Instant snapshotAt,
            Instant afterCreatedAt,
            UUID afterId,
            int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException(
                    "review page limit must be between 1 and 500");
        }
        String pagePredicate =
                afterCreatedAt == null || afterId == null
                        ? ""
                        : " AND (created_at, id) > (?, ?)";
        String sql = """
                SELECT id, identity_id, target_kind, role_id, entitlement_id,
                       principal_constraint_kind, specific_principal_id,
                       provenance_kind, provenance_ref_id, lifecycle_state,
                       valid_from, valid_until, revision, created_at, updated_at
                FROM access.access_assignment
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND created_at <= ?
                  AND lifecycle_state IN ('ACTIVE','SUSPENDED','SCHEDULED')
                  AND (valid_until IS NULL OR valid_until > ?)
                """ + pagePredicate + """
                ORDER BY created_at, id
                LIMIT ?
                """;
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenant.tenantId());
        args.add(identityId);
        args.add(Timestamp.from(snapshotAt));
        args.add(Timestamp.from(snapshotAt));
        if (!pagePredicate.isEmpty()) {
            args.add(Timestamp.from(afterCreatedAt));
            args.add(afterId);
        }
        args.add(limit);
        return jdbc.query(
                sql,
                (rs,row) -> assignment(rs),
                args.toArray());
    }

    @Override
    public AccessAssignment updateLifecycle(
            TenantContext tenant,
            UUID assignmentId,
            AccessAssignment.LifecycleState lifecycleState,
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
                lifecycleState.name(),
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
                    "AccessAssignment lifecycle did not update");
        }
        return findById(tenant, assignmentId).orElseThrow();
    }

    @Override
    public AccessAssignment terminate(
            TenantContext tenant,
            UUID assignmentId,
            AccessAssignment.LifecycleState terminalState,
            long expectedRevision,
            Instant now) {
        return updateLifecycle(
                tenant,
                assignmentId,
                terminalState,
                expectedRevision,
                now);
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
