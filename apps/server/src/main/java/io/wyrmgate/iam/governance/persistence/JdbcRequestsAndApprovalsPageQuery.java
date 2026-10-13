package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.RequestsAndApprovalsPageQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRequestsAndApprovalsPageQuery implements RequestsAndApprovalsPageQuery {
    private static final String IN_FLIGHT = "'SUBMITTED','EVALUATING','PENDING_APPROVAL','AUTHORIZED'";
    private final JdbcTemplate jdbc;

    public JdbcRequestsAndApprovalsPageQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Snapshot snapshot(
            TenantContext tenant,
            UUID actorIdentityId,
            Instant from,
            Instant until,
            ApprovalSort approvalSort,
            int approvalLimit,
            int requestLimit) {
        if (approvalLimit < 1 || approvalLimit > 50) {
            throw new IllegalArgumentException("approval limit must be between 1 and 50");
        }
        if (requestLimit < 1 || requestLimit > 50) {
            throw new IllegalArgumentException("request limit must be between 1 and 50");
        }
        return new Snapshot(
                countPendingApprovals(tenant, actorIdentityId),
                countDraftRequests(tenant, actorIdentityId),
                countInFlightItems(tenant, actorIdentityId),
                countAppliedItems(tenant, actorIdentityId, from, until),
                requestStatusTotals(tenant, actorIdentityId),
                approvalWork(tenant, actorIdentityId, approvalSort, approvalLimit),
                recentRequests(tenant, actorIdentityId, requestLimit));
    }

    private long countPendingApprovals(TenantContext tenant, UUID actor) {
        Long value = jdbc.queryForObject("""
                SELECT count(DISTINCT c.id)
                FROM governance.approval_case c
                JOIN governance.approval_plan p
                  ON p.tenant_id = c.tenant_id AND p.approval_case_id = c.id
                JOIN governance.approval_stage s
                  ON s.tenant_id = p.tenant_id
                 AND s.approval_plan_id = p.id
                 AND s.stage_ordinal = c.current_stage_ordinal
                JOIN governance.approval_approver a
                  ON a.tenant_id = s.tenant_id
                 AND a.approval_stage_id = s.id
                 AND a.approver_identity_id = ?
                LEFT JOIN governance.approval_decision d
                  ON d.tenant_id = s.tenant_id
                 AND d.approval_stage_id = s.id
                 AND d.approver_identity_id = a.approver_identity_id
                WHERE c.tenant_id = ? AND c.state = 'PENDING' AND d.id IS NULL
                """, Long.class, actor, tenant.tenantId());
        return value == null ? 0 : value;
    }

    private long countDraftRequests(TenantContext tenant, UUID actor) {
        Long value = jdbc.queryForObject("""
                SELECT count(*)
                FROM governance.access_request
                WHERE tenant_id = ?
                  AND requester_identity_id = ?
                  AND beneficiary_identity_id = ?
                  AND state = 'DRAFT'
                """, Long.class, tenant.tenantId(), actor, actor);
        return value == null ? 0 : value;
    }

    private long countInFlightItems(TenantContext tenant, UUID actor) {
        Long value = jdbc.queryForObject("""
                SELECT count(*)
                FROM governance.request_item i
                JOIN governance.access_request r
                  ON r.tenant_id = i.tenant_id AND r.id = i.access_request_id
                WHERE i.tenant_id = ?
                  AND r.requester_identity_id = ?
                  AND r.beneficiary_identity_id = ?
                  AND i.state IN ('SUBMITTED','EVALUATING','PENDING_APPROVAL','AUTHORIZED')
                """, Long.class, tenant.tenantId(), actor, actor);
        return value == null ? 0 : value;
    }

    private long countAppliedItems(TenantContext tenant, UUID actor, Instant from, Instant until) {
        Long value = jdbc.queryForObject("""
                SELECT count(*)
                FROM governance.request_item i
                JOIN governance.access_request r
                  ON r.tenant_id = i.tenant_id AND r.id = i.access_request_id
                WHERE i.tenant_id = ?
                  AND r.requester_identity_id = ?
                  AND r.beneficiary_identity_id = ?
                  AND i.state = 'APPLIED'
                  AND i.updated_at >= ? AND i.updated_at < ?
                """, Long.class, tenant.tenantId(), actor, actor, Timestamp.from(from), Timestamp.from(until));
        return value == null ? 0 : value;
    }

    private RequestStatusTotals requestStatusTotals(TenantContext tenant, UUID actor) {
        Map<String,Object> row = jdbc.queryForMap("""
                WITH request_status AS (
                    SELECT r.id,
                           CASE
                             WHEN r.state = 'DRAFT' THEN 'DRAFT'
                             WHEN count(*) FILTER (WHERE i.state IN ('SUBMITTED','EVALUATING','PENDING_APPROVAL','AUTHORIZED')) > 0
                               THEN 'IN_PROGRESS'
                             WHEN count(*) FILTER (WHERE i.state = 'APPLIED') = count(i.id)
                               THEN 'COMPLETED_APPLIED'
                             WHEN count(*) FILTER (WHERE i.state = 'APPLIED') > 0
                               THEN 'COMPLETED_MIXED'
                             ELSE 'COMPLETED_NO_ACCESS'
                           END AS presentation_status
                    FROM governance.access_request r
                    JOIN governance.request_item i
                      ON i.tenant_id = r.tenant_id AND i.access_request_id = r.id
                    WHERE r.tenant_id = ?
                      AND r.requester_identity_id = ?
                      AND r.beneficiary_identity_id = ?
                    GROUP BY r.id, r.state
                )
                SELECT count(*) FILTER (WHERE presentation_status = 'DRAFT') AS draft,
                       count(*) FILTER (WHERE presentation_status = 'IN_PROGRESS') AS in_progress,
                       count(*) FILTER (WHERE presentation_status = 'COMPLETED_APPLIED') AS completed_applied,
                       count(*) FILTER (WHERE presentation_status = 'COMPLETED_MIXED') AS completed_mixed,
                       count(*) FILTER (WHERE presentation_status = 'COMPLETED_NO_ACCESS') AS completed_no_access
                FROM request_status
                """, tenant.tenantId(), actor, actor);
        return new RequestStatusTotals(
                number(row.get("draft")),
                number(row.get("in_progress")),
                number(row.get("completed_applied")),
                number(row.get("completed_mixed")),
                number(row.get("completed_no_access")));
    }

    private List<ApprovalWork> approvalWork(
            TenantContext tenant, UUID actor, ApprovalSort sort, int limit) {
        String direction = sort == ApprovalSort.NEWEST ? "DESC" : "ASC";
        String sql = """
                SELECT DISTINCT c.id, c.subject_kind, c.subject_id,
                       c.current_stage_ordinal, c.created_at,
                       ri.access_request_id, ri.target_kind,
                       COALESCE(ri.role_id, ri.entitlement_id) AS target_id
                FROM governance.approval_case c
                JOIN governance.approval_plan p
                  ON p.tenant_id = c.tenant_id AND p.approval_case_id = c.id
                JOIN governance.approval_stage s
                  ON s.tenant_id = p.tenant_id
                 AND s.approval_plan_id = p.id
                 AND s.stage_ordinal = c.current_stage_ordinal
                JOIN governance.approval_approver a
                  ON a.tenant_id = s.tenant_id
                 AND a.approval_stage_id = s.id
                 AND a.approver_identity_id = ?
                LEFT JOIN governance.approval_decision d
                  ON d.tenant_id = s.tenant_id
                 AND d.approval_stage_id = s.id
                 AND d.approver_identity_id = a.approver_identity_id
                LEFT JOIN governance.request_item ri
                  ON c.subject_kind = 'ACCESS_REQUEST_ITEM'
                 AND ri.tenant_id = c.tenant_id
                 AND ri.id = c.subject_id
                WHERE c.tenant_id = ? AND c.state = 'PENDING' AND d.id IS NULL
                ORDER BY c.created_at %s, c.id %s
                LIMIT ?
                """.formatted(direction, direction);
        return jdbc.query(sql, (rs, row) -> approvalRow(rs), actor, tenant.tenantId(), limit);
    }

    private List<RequestSummary> recentRequests(TenantContext tenant, UUID actor, int limit) {
        return jdbc.query("""
                SELECT r.id, r.state, r.created_at, r.updated_at,
                       count(i.id) AS item_count,
                       count(*) FILTER (WHERE i.state = 'DRAFT') AS draft_items,
                       count(*) FILTER (WHERE i.state = 'SUBMITTED') AS submitted_items,
                       count(*) FILTER (WHERE i.state = 'EVALUATING') AS evaluating_items,
                       count(*) FILTER (WHERE i.state = 'PENDING_APPROVAL') AS pending_approval_items,
                       count(*) FILTER (WHERE i.state = 'AUTHORIZED') AS authorized_items,
                       count(*) FILTER (WHERE i.state = 'APPLIED') AS applied_items,
                       count(*) FILTER (WHERE i.state = 'DENIED') AS denied_items,
                       count(*) FILTER (WHERE i.state = 'REJECTED') AS rejected_items,
                       count(*) FILTER (WHERE i.state = 'CANCELLED') AS cancelled_items,
                       count(*) FILTER (WHERE i.state = 'EXPIRED') AS expired_items,
                       CASE WHEN count(i.id) = 1 THEN min(i.target_kind) END AS single_target_kind,
                       CASE WHEN count(i.id) = 1 THEN (array_agg(COALESCE(i.role_id, i.entitlement_id) ORDER BY i.created_at, i.id))[1] END AS single_target_id
                FROM governance.access_request r
                JOIN governance.request_item i
                  ON i.tenant_id = r.tenant_id AND i.access_request_id = r.id
                WHERE r.tenant_id = ?
                  AND r.requester_identity_id = ?
                  AND r.beneficiary_identity_id = ?
                GROUP BY r.id, r.state, r.created_at, r.updated_at
                ORDER BY r.updated_at DESC, r.id DESC
                LIMIT ?
                """, (rs, row) -> requestRow(rs), tenant.tenantId(), actor, actor, limit);
    }

    private static ApprovalWork approvalRow(ResultSet rs) throws SQLException {
        return new ApprovalWork(
                rs.getObject("id", UUID.class),
                rs.getString("subject_kind"),
                rs.getObject("subject_id", UUID.class),
                rs.getObject("access_request_id", UUID.class),
                rs.getString("target_kind"),
                rs.getObject("target_id", UUID.class),
                rs.getInt("current_stage_ordinal"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static RequestSummary requestRow(ResultSet rs) throws SQLException {
        int itemCount = rs.getInt("item_count");
        int draft = rs.getInt("draft_items");
        int submitted = rs.getInt("submitted_items");
        int evaluating = rs.getInt("evaluating_items");
        int pendingApproval = rs.getInt("pending_approval_items");
        int authorized = rs.getInt("authorized_items");
        int applied = rs.getInt("applied_items");
        int denied = rs.getInt("denied_items");
        int rejected = rs.getInt("rejected_items");
        int cancelled = rs.getInt("cancelled_items");
        int expired = rs.getInt("expired_items");
        String status = presentationStatus(
                rs.getString("state"), itemCount, submitted, evaluating,
                pendingApproval, authorized, applied);
        return new RequestSummary(
                rs.getObject("id", UUID.class), status, itemCount,
                draft, submitted, evaluating, pendingApproval, authorized,
                applied, denied, rejected, cancelled, expired,
                rs.getString("single_target_kind"),
                rs.getObject("single_target_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String presentationStatus(
            String envelopeState,
            int itemCount,
            int submitted,
            int evaluating,
            int pendingApproval,
            int authorized,
            int applied) {
        if ("DRAFT".equals(envelopeState)) return "DRAFT";
        if (submitted + evaluating + pendingApproval + authorized > 0) return "IN_PROGRESS";
        if (applied == itemCount) return "COMPLETED_APPLIED";
        if (applied > 0) return "COMPLETED_MIXED";
        return "COMPLETED_NO_ACCESS";
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
