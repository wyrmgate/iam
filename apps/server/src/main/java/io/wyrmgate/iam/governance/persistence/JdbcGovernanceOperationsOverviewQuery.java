package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.GovernanceOperationsOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcGovernanceOperationsOverviewQuery implements GovernanceOperationsOverviewQuery {
    private final JdbcTemplate jdbc;

    public JdbcGovernanceOperationsOverviewQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Snapshot snapshot(TenantContext tenant, UUID actorIdentityId, Instant from, Instant until,
            WorkType workType, WorkSort workSort, int limit) {
        if (limit < 1 || limit > 10) throw new IllegalArgumentException("overview work limit must be between 1 and 10");
        long approvals = countPendingApprovals(tenant, actorIdentityId);
        long reviews = countPendingReviews(tenant, actorIdentityId);
        DecisionTotals decisions = decisionTotals(tenant, actorIdentityId, from, until);
        List<WorkItem> candidates = new ArrayList<>();
        if (workType != WorkType.REVIEW) candidates.addAll(approvalWork(tenant, actorIdentityId, limit));
        if (workType != WorkType.APPROVAL) candidates.addAll(reviewWork(tenant, actorIdentityId, limit));
        Comparator<WorkItem> comparator = Comparator.comparing(WorkItem::createdAt).thenComparing(WorkItem::id);
        if (workSort == WorkSort.NEWEST) comparator = comparator.reversed();
        return new Snapshot(approvals, reviews, decisions, candidates.stream().sorted(comparator).limit(limit).toList());
    }

    private long countPendingApprovals(TenantContext tenant, UUID actor) {
        Long value = jdbc.queryForObject("""
                SELECT count(DISTINCT c.id)
                FROM governance.approval_case c
                JOIN governance.approval_plan p ON p.tenant_id = c.tenant_id AND p.approval_case_id = c.id
                JOIN governance.approval_stage s ON s.tenant_id = p.tenant_id AND s.approval_plan_id = p.id AND s.stage_ordinal = c.current_stage_ordinal
                JOIN governance.approval_approver a ON a.tenant_id = s.tenant_id AND a.approval_stage_id = s.id AND a.approver_identity_id = ?
                LEFT JOIN governance.approval_decision d ON d.tenant_id = s.tenant_id AND d.approval_stage_id = s.id AND d.approver_identity_id = a.approver_identity_id
                WHERE c.tenant_id = ? AND c.state = 'PENDING' AND d.id IS NULL
                """, Long.class, actor, tenant.tenantId());
        return value == null ? 0 : value;
    }

    private long countPendingReviews(TenantContext tenant, UUID actor) {
        Long value = jdbc.queryForObject("""
                SELECT count(*) FROM governance.review_item
                WHERE tenant_id = ? AND reviewer_identity_id = ? AND lifecycle_state = 'PENDING'
                """, Long.class, tenant.tenantId(), actor);
        return value == null ? 0 : value;
    }

    private DecisionTotals decisionTotals(TenantContext tenant, UUID actor, Instant from, Instant until) {
        Map<String,Object> approvalCounts = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE decision = 'APPROVE') AS approvals,
                       count(*) FILTER (WHERE decision = 'REJECT') AS rejections
                FROM governance.approval_decision
                WHERE tenant_id = ? AND approver_identity_id = ? AND decided_at >= ? AND decided_at < ?
                """, tenant.tenantId(), actor, Timestamp.from(from), Timestamp.from(until));
        Map<String,Object> reviewCounts = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE decision = 'KEEP') AS keeps,
                       count(*) FILTER (WHERE decision = 'REVOKE') AS revokes
                FROM governance.review_decision
                WHERE tenant_id = ? AND reviewer_identity_id = ? AND decided_at >= ? AND decided_at < ?
                """, tenant.tenantId(), actor, Timestamp.from(from), Timestamp.from(until));
        return new DecisionTotals(number(approvalCounts.get("approvals")), number(approvalCounts.get("rejections")),
                number(reviewCounts.get("keeps")), number(reviewCounts.get("revokes")));
    }

    private List<WorkItem> approvalWork(TenantContext tenant, UUID actor, int limit) {
        return jdbc.query("""
                SELECT DISTINCT c.id, c.subject_kind, c.subject_id, c.current_stage_ordinal, c.created_at
                FROM governance.approval_case c
                JOIN governance.approval_plan p ON p.tenant_id = c.tenant_id AND p.approval_case_id = c.id
                JOIN governance.approval_stage s ON s.tenant_id = p.tenant_id AND s.approval_plan_id = p.id AND s.stage_ordinal = c.current_stage_ordinal
                JOIN governance.approval_approver a ON a.tenant_id = s.tenant_id AND a.approval_stage_id = s.id AND a.approver_identity_id = ?
                LEFT JOIN governance.approval_decision d ON d.tenant_id = s.tenant_id AND d.approval_stage_id = s.id AND d.approver_identity_id = a.approver_identity_id
                WHERE c.tenant_id = ? AND c.state = 'PENDING' AND d.id IS NULL
                ORDER BY c.created_at, c.id LIMIT ?
                """, (rs,row) -> new WorkItem("APPROVAL", rs.getObject("id", UUID.class), rs.getString("subject_kind"),
                        rs.getObject("subject_id", UUID.class), rs.getInt("current_stage_ordinal"), rs.getTimestamp("created_at").toInstant()),
                actor, tenant.tenantId(), limit);
    }

    private List<WorkItem> reviewWork(TenantContext tenant, UUID actor, int limit) {
        return jdbc.query("""
                SELECT id, target_kind, COALESCE(role_id, entitlement_id) AS subject_id, created_at
                FROM governance.review_item
                WHERE tenant_id = ? AND reviewer_identity_id = ? AND lifecycle_state = 'PENDING'
                ORDER BY created_at, id LIMIT ?
                """, (rs,row) -> new WorkItem("REVIEW", rs.getObject("id", UUID.class), rs.getString("target_kind"),
                        rs.getObject("subject_id", UUID.class), 0, rs.getTimestamp("created_at").toInstant()),
                tenant.tenantId(), actor, limit);
    }

    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
}
