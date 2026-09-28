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
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcAccessRequestRepository
        implements AccessRequestRepository {

    private final JdbcTemplate jdbc;

    public JdbcAccessRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertRequest(
            TenantContext tenant, AccessRequest request) {
        jdbc.update("""
                INSERT INTO governance.access_request (
                    id, tenant_id, requester_identity_id,
                    beneficiary_identity_id, lifecycle_state,
                    revision, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.id(),
                tenant.tenantId(),
                request.requesterIdentityId(),
                request.beneficiaryIdentityId(),
                request.lifecycleState().name(),
                request.revision(),
                Timestamp.from(request.createdAt()),
                Timestamp.from(request.updatedAt()),
                timestamp(request.completedAt()));
    }

    @Override
    public void insertItem(
            TenantContext tenant, RequestItem item) {
        jdbc.update("""
                INSERT INTO governance.request_item (
                    id, tenant_id, access_request_id,
                    target_kind, role_id, entitlement_id,
                    principal_constraint_kind, specific_principal_id,
                    valid_from, valid_until, lifecycle_state,
                    approval_case_id, access_assignment_id,
                    revision, created_at, updated_at, completed_at)
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
                item.approvalCaseId(),
                item.accessAssignmentId(),
                item.revision(),
                Timestamp.from(item.createdAt()),
                Timestamp.from(item.updatedAt()),
                timestamp(item.completedAt()));
    }

    @Override
    public Optional<AccessRequest> findRequest(
            TenantContext tenant, UUID requestId) {
        return jdbc.query("""
                SELECT id, requester_identity_id, beneficiary_identity_id,
                       lifecycle_state, revision,
                       created_at, updated_at, completed_at
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
            TenantContext tenant, UUID requestItemId) {
        return jdbc.query("""
                SELECT id, access_request_id, target_kind, role_id, entitlement_id,
                       principal_constraint_kind, specific_principal_id,
                       valid_from, valid_until, lifecycle_state,
                       approval_case_id, access_assignment_id,
                       revision, created_at, updated_at, completed_at
                FROM governance.request_item
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> item(rs),
                tenant.tenantId(),
                requestItemId)
                .stream()
                .findFirst();
    }

    @Override
    public List<RequestItem> findItems(
            TenantContext tenant, UUID requestId) {
        return jdbc.query("""
                SELECT id, access_request_id, target_kind, role_id, entitlement_id,
                       principal_constraint_kind, specific_principal_id,
                       valid_from, valid_until, lifecycle_state,
                       approval_case_id, access_assignment_id,
                       revision, created_at, updated_at, completed_at
                FROM governance.request_item
                WHERE tenant_id = ? AND access_request_id = ?
                ORDER BY created_at, id
                """,
                (rs,row) -> item(rs),
                tenant.tenantId(),
                requestId);
    }

    @Override
    public Optional<RequestItem> findItemByApprovalCase(
            TenantContext tenant, UUID approvalCaseId) {
        return jdbc.query("""
                SELECT id, access_request_id, target_kind, role_id, entitlement_id,
                       principal_constraint_kind, specific_principal_id,
                       valid_from, valid_until, lifecycle_state,
                       approval_case_id, access_assignment_id,
                       revision, created_at, updated_at, completed_at
                FROM governance.request_item
                WHERE tenant_id = ? AND approval_case_id = ?
                """,
                (rs,row) -> item(rs),
                tenant.tenantId(),
                approvalCaseId)
                .stream()
                .findFirst();
    }

    @Override
    public AccessRequest updateRequestState(
            TenantContext tenant,
            UUID requestId,
            long expectedRevision,
            AccessRequest.LifecycleState state,
            Instant now,
            Instant completedAt) {
        int affected = jdbc.update("""
                UPDATE governance.access_request
                SET lifecycle_state = ?,
                    revision = revision + 1,
                    updated_at = ?,
                    completed_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                """,
                state.name(),
                Timestamp.from(now),
                timestamp(completedAt),
                tenant.tenantId(),
                requestId,
                expectedRevision);
        if (affected != 1) {
            AccessRequest current = findRequest(tenant, requestId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "AccessRequest does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "access-request", requestId, expectedRevision);
            }
            throw new IllegalStateException(
                    "AccessRequest state did not update");
        }
        return findRequest(tenant, requestId).orElseThrow();
    }

    @Override
    public RequestItem updateItemState(
            TenantContext tenant,
            UUID requestItemId,
            long expectedRevision,
            RequestItem.LifecycleState state,
            UUID approvalCaseId,
            UUID accessAssignmentId,
            Instant now,
            Instant completedAt) {
        int affected = jdbc.update("""
                UPDATE governance.request_item
                SET lifecycle_state = ?,
                    approval_case_id = ?,
                    access_assignment_id = ?,
                    revision = revision + 1,
                    updated_at = ?,
                    completed_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                """,
                state.name(),
                approvalCaseId,
                accessAssignmentId,
                Timestamp.from(now),
                timestamp(completedAt),
                tenant.tenantId(),
                requestItemId,
                expectedRevision);
        if (affected != 1) {
            RequestItem current = findItem(tenant, requestItemId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "RequestItem does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "request-item", requestItemId, expectedRevision);
            }
            throw new IllegalStateException(
                    "RequestItem state did not update");
        }
        return findItem(tenant, requestItemId).orElseThrow();
    }

    private static AccessRequest request(
            ResultSet rs) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new AccessRequest(
                rs.getObject("id", UUID.class),
                rs.getObject("requester_identity_id", UUID.class),
                rs.getObject("beneficiary_identity_id", UUID.class),
                AccessRequest.LifecycleState.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private static RequestItem item(
            ResultSet rs) throws SQLException {
        Timestamp validFrom = rs.getTimestamp("valid_from");
        Timestamp validUntil = rs.getTimestamp("valid_until");
        Timestamp completed = rs.getTimestamp("completed_at");
        return new RequestItem(
                rs.getObject("id", UUID.class),
                rs.getObject("access_request_id", UUID.class),
                RequestItem.TargetKind.valueOf(
                        rs.getString("target_kind")),
                rs.getObject("role_id", UUID.class),
                rs.getObject("entitlement_id", UUID.class),
                RequestItem.PrincipalConstraintKind.valueOf(
                        rs.getString("principal_constraint_kind")),
                rs.getObject("specific_principal_id", UUID.class),
                validFrom == null ? null : validFrom.toInstant(),
                validUntil == null ? null : validUntil.toInstant(),
                RequestItem.LifecycleState.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getObject("approval_case_id", UUID.class),
                rs.getObject("access_assignment_id", UUID.class),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
