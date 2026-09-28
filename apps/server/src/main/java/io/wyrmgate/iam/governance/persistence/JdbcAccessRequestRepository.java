package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.AccessRequestModels.AccessRequest;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemState;
import io.wyrmgate.iam.governance.application.AccessRequestModels.PrincipalConstraintKind;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestState;
import io.wyrmgate.iam.governance.application.AccessRequestModels.TargetKind;
import io.wyrmgate.iam.governance.application.AccessRequestRepository;
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
            TenantContext tenant,
            AccessRequest request) {
        jdbc.update("""
                INSERT INTO governance.access_request (
                    id, tenant_id, requester_identity_id,
                    beneficiary_identity_id, state, revision,
                    created_at, submitted_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.id(),
                tenant.tenantId(),
                request.requesterIdentityId(),
                request.beneficiaryIdentityId(),
                request.state().name(),
                request.revision(),
                Timestamp.from(request.createdAt()),
                request.submittedAt() == null
                        ? null
                        : Timestamp.from(request.submittedAt()),
                Timestamp.from(request.updatedAt()));
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
                    valid_from, valid_until, state,
                    approval_case_id, access_assignment_id, evaluation_code,
                    revision, created_at, updated_at)
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
                item.state().name(),
                item.approvalCaseId(),
                item.accessAssignmentId(),
                item.evaluationCode(),
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
                       beneficiary_identity_id, state, revision,
                       created_at, submitted_at, updated_at
                FROM governance.access_request
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> requestRow(rs),
                tenant.tenantId(),
                requestId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<RequestItem> findItem(
            TenantContext tenant,
            UUID itemId) {
        return jdbc.query("""
                SELECT id, access_request_id, target_kind,
                       role_id, entitlement_id,
                       principal_constraint_kind, specific_principal_id,
                       valid_from, valid_until, state,
                       approval_case_id, access_assignment_id, evaluation_code,
                       revision, created_at, updated_at
                FROM governance.request_item
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> itemRow(rs),
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
                       principal_constraint_kind, specific_principal_id,
                       valid_from, valid_until, state,
                       approval_case_id, access_assignment_id, evaluation_code,
                       revision, created_at, updated_at
                FROM governance.request_item
                WHERE tenant_id = ?
                  AND access_request_id = ?
                ORDER BY created_at, id
                """,
                (rs,row) -> itemRow(rs),
                tenant.tenantId(),
                requestId);
    }

    @Override
    public AccessRequest updateRequestState(
            TenantContext tenant,
            UUID requestId,
            RequestState state,
            long expectedRevision,
            Instant now,
            Instant submittedAt) {
        int affected = jdbc.update("""
                UPDATE governance.access_request
                SET state = ?,
                    revision = revision + 1,
                    submitted_at = COALESCE(submitted_at, ?),
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                """,
                state.name(),
                submittedAt == null
                        ? null
                        : Timestamp.from(submittedAt),
                Timestamp.from(now),
                tenant.tenantId(),
                requestId,
                expectedRevision);
        requireRequestUpdate(
                tenant, requestId, expectedRevision, affected);
        return findRequest(tenant, requestId).orElseThrow();
    }

    @Override
    public RequestItem updateItemState(
            TenantContext tenant,
            UUID itemId,
            ItemState state,
            UUID approvalCaseId,
            UUID accessAssignmentId,
            String evaluationCode,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE governance.request_item
                SET state = ?,
                    approval_case_id = ?,
                    access_assignment_id = ?,
                    evaluation_code = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revision = ?
                """,
                state.name(),
                approvalCaseId,
                accessAssignmentId,
                evaluationCode,
                Timestamp.from(now),
                tenant.tenantId(),
                itemId,
                expectedRevision);
        requireItemUpdate(
                tenant, itemId, expectedRevision, affected);
        return findItem(tenant, itemId).orElseThrow();
    }

    private void requireRequestUpdate(
            TenantContext tenant,
            UUID requestId,
            long expectedRevision,
            int affected) {
        if (affected == 1) return;
        AccessRequest current = findRequest(tenant, requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "access request does not exist"));
        throw new StaleWriteException(
                "access-request",
                current.id(),
                expectedRevision);
    }

    private void requireItemUpdate(
            TenantContext tenant,
            UUID itemId,
            long expectedRevision,
            int affected) {
        if (affected == 1) return;
        RequestItem current = findItem(tenant, itemId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "request item does not exist"));
        throw new StaleWriteException(
                "request-item",
                current.id(),
                expectedRevision);
    }

    private static AccessRequest requestRow(ResultSet rs)
            throws SQLException {
        Timestamp submitted = rs.getTimestamp("submitted_at");
        return new AccessRequest(
                rs.getObject("id", UUID.class),
                rs.getObject("requester_identity_id", UUID.class),
                rs.getObject("beneficiary_identity_id", UUID.class),
                RequestState.valueOf(rs.getString("state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                submitted == null ? null : submitted.toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static RequestItem itemRow(ResultSet rs)
            throws SQLException {
        return new RequestItem(
                rs.getObject("id", UUID.class),
                rs.getObject("access_request_id", UUID.class),
                TargetKind.valueOf(rs.getString("target_kind")),
                rs.getObject("role_id", UUID.class),
                rs.getObject("entitlement_id", UUID.class),
                PrincipalConstraintKind.valueOf(
                        rs.getString("principal_constraint_kind")),
                rs.getObject("specific_principal_id", UUID.class),
                instant(rs.getTimestamp("valid_from")),
                instant(rs.getTimestamp("valid_until")),
                ItemState.valueOf(rs.getString("state")),
                rs.getObject("approval_case_id", UUID.class),
                rs.getObject("access_assignment_id", UUID.class),
                rs.getString("evaluation_code"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
