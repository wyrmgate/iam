package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.AccessRequestRepository;
import io.wyrmgate.iam.governance.domain.AccessRequest;
import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAccessRequestRepository
        implements AccessRequestRepository {

    private final JdbcTemplate jdbc;

    public JdbcAccessRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insertRequest(
            TenantContext tenant,
            AccessRequest request) {
        jdbc.update("""
                INSERT INTO governance.access_request (
                    id, tenant_id, requester_identity_id,
                    beneficiary_identity_id, lifecycle_state,
                    revision, created_at, updated_at,
                    submitted_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.id(),
                tenant.tenantId(),
                request.requesterIdentityId(),
                request.beneficiaryIdentityId(),
                request.lifecycleState().name(),
                request.revision(),
                Timestamp.from(request.createdAt()),
                Timestamp.from(request.updatedAt()),
                timestamp(request.submittedAt()),
                timestamp(request.completedAt()));
    }

    @Override
    public void insertItem(
            TenantContext tenant,
            RequestItem item) {
        jdbc.update("""
                INSERT INTO governance.request_item (
                    id, tenant_id, access_request_id,
                    target_kind, role_id, entitlement_id,
                    principal_constraint_kind, specific_principal_id,
                    valid_from, valid_until, lifecycle_state,
                    approval_plan_id, access_assignment_id,
                    denial_code, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                item.id(),
                tenant.tenantId(),
                item.accessRequestId(),
                item.targetKind().name(),
                item.roleId(),
                item.entitlementId(),
                item.principalConstraintKind().name(),
                item.specificPrincipalId(),
                timestamp(item.validFrom()),
                timestamp(item.validUntil()),
                item.lifecycleState().name(),
                item.approvalPlanId(),
                item.accessAssignmentId(),
                item.denialCode(),
                item.revision(),
                Timestamp.from(item.createdAt()),
                Timestamp.from(item.updatedAt()));
    }

    @Override
    public Optional<AccessRequest> findRequest(
            TenantContext tenant,
            UUID requestId) {
        return jdbc.query("""
                SELECT id, requester_identity_id,
                       beneficiary_identity_id, lifecycle_state,
                       revision, created_at, updated_at,
                       submitted_at, completed_at
                FROM governance.access_request
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> request(rs),
                tenant.tenantId(),
                requestId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<RequestItem> findItem(
            TenantContext tenant, UUID itemId) {
        return jdbc.query("""
                SELECT id, access_request_id, target_kind,
                       role_id, entitlement_id,
                       principal_constraint_kind,
                       specific_principal_id,
                       valid_from, valid_until,
                       lifecycle_state, approval_plan_id,
                       access_assignment_id, denial_code,
                       revision, created_at, updated_at
                FROM governance.request_item
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> item(rs),
                tenant.tenantId(),
                itemId)
                .stream()
                .findFirst();
    }

    @Override
    public List<RequestItem> findItems(
            TenantContext tenant,
            UUID requestId) {
        return jdbc.query("""
                SELECT id, access_request_id, target_kind,
                       role_id, entitlement_id,
                       principal_constraint_kind,
                       specific_principal_id,
                       valid_from, valid_until,
                       lifecycle_state, approval_plan_id,
                       access_assignment_id, denial_code,
                       revision, created_at, updated_at
                FROM governance.request_item
                WHERE tenant_id = ?
                  AND access_request_id = ?
                ORDER BY created_at, id
                """,
                (rs,row) -> item(rs),
                tenant.tenantId(),
                requestId);
    }

    @Override
    public AccessRequest updateRequestState(
            TenantContext tenant,
            UUID requestId,
            AccessRequest.LifecycleState state,
            long expectedRevision,
            Instant now,
            Instant submittedAt,
            Instant completedAt) {
        int affected = jdbc.update("""
                UPDATE governance.access_request
                SET lifecycle_state = ?,
                    revision = revision + 1,
                    updated_at = ?,
                    submitted_at = COALESCE(?, submitted_at),
                    completed_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                """,
                state.name(),
                Timestamp.from(now),
                timestamp(submittedAt),
                timestamp(completedAt),
                tenant.tenantId(),
                requestId,
                expectedRevision);
        if (affected != 1) {
            AccessRequest current = findRequest(
                            tenant, requestId)
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "AccessRequest does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "access-request",
                        requestId,
                        expectedRevision);
            }
            throw new IllegalStateException(
                    "AccessRequest did not update");
        }
        return findRequest(tenant, requestId).orElseThrow();
    }

    @Override
    public RequestItem updateItemState(
            TenantContext tenant,
            UUID itemId,
            RequestItem.LifecycleState state,
            UUID approvalPlanId,
            UUID accessAssignmentId,
            String denialCode,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE governance.request_item
                SET lifecycle_state = ?,
                    approval_plan_id = ?,
                    access_assignment_id = ?,
                    denial_code = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                """,
                state.name(),
                approvalPlanId,
                accessAssignmentId,
                denialCode,
                Timestamp.from(now),
                tenant.tenantId(),
                itemId,
                expectedRevision);
        if (affected != 1) {
            RequestItem current = findItem(
                            tenant, itemId)
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "RequestItem does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "request-item",
                        itemId,
                        expectedRevision);
            }
            throw new IllegalStateException(
                    "RequestItem did not update");
        }
        return findItem(tenant, itemId).orElseThrow();
    }

    private static AccessRequest request(
            ResultSet rs) throws SQLException {
        Timestamp submitted =
                rs.getTimestamp("submitted_at");
        Timestamp completed =
                rs.getTimestamp("completed_at");
        return new AccessRequest(
                rs.getObject("id", UUID.class),
                rs.getObject(
                        "requester_identity_id",
                        UUID.class),
                rs.getObject(
                        "beneficiary_identity_id",
                        UUID.class),
                AccessRequest.LifecycleState.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                submitted == null
                        ? null
                        : submitted.toInstant(),
                completed == null
                        ? null
                        : completed.toInstant());
    }

    private static RequestItem item(
            ResultSet rs) throws SQLException {
        Timestamp validFrom =
                rs.getTimestamp("valid_from");
        Timestamp validUntil =
                rs.getTimestamp("valid_until");
        return new RequestItem(
                rs.getObject("id", UUID.class),
                rs.getObject(
                        "access_request_id",
                        UUID.class),
                RequestItem.TargetKind.valueOf(
                        rs.getString("target_kind")),
                rs.getObject("role_id", UUID.class),
                rs.getObject(
                        "entitlement_id",
                        UUID.class),
                RequestItem.PrincipalConstraintKind.valueOf(
                        rs.getString(
                                "principal_constraint_kind")),
                rs.getObject(
                        "specific_principal_id",
                        UUID.class),
                validFrom == null
                        ? null
                        : validFrom.toInstant(),
                validUntil == null
                        ? null
                        : validUntil.toInstant(),
                RequestItem.LifecycleState.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getObject(
                        "approval_plan_id",
                        UUID.class),
                rs.getObject(
                        "access_assignment_id",
                        UUID.class),
                rs.getString("denial_code"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp timestamp(
            Instant value) {
        return value == null
                ? null
                : Timestamp.from(value);
    }
}
