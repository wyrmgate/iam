package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.governance.application.LifecycleAccessApprovalCandidateRepository;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcLifecycleAccessApprovalCandidateRepository
        implements LifecycleAccessApprovalCandidateRepository {
    private final JdbcTemplate jdbc;
    public JdbcLifecycleAccessApprovalCandidateRepository(JdbcTemplate jdbc) {
        this.jdbc=java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<Candidate> findByContext(
            TenantContext tenant, UUID identityId, UUID lifecycleRuleId,
            AccessAssignment.TargetKind targetKind, UUID targetId,
            UUID policyVersionId, String approvalPlanHash) {
        return jdbc.query("""
                SELECT id, identity_id, lifecycle_rule_id, target_kind, target_id,
                       policy_version_id, approval_plan_hash, correlation_id, causation_id, created_at
                FROM governance.lifecycle_access_approval_candidate
                WHERE tenant_id=? AND identity_id=? AND lifecycle_rule_id=? AND target_kind=?
                  AND target_id=? AND policy_version_id=? AND approval_plan_hash=?
                """, (rs,n)->map(rs), tenant.tenantId(), identityId, lifecycleRuleId,
                targetKind.name(), targetId, policyVersionId, approvalPlanHash).stream().findFirst();
    }

    @Override
    public Optional<Candidate> findById(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT id, identity_id, lifecycle_rule_id, target_kind, target_id,
                       policy_version_id, approval_plan_hash, correlation_id, causation_id, created_at
                FROM governance.lifecycle_access_approval_candidate
                WHERE tenant_id=? AND id=?
                """, (rs,n)->map(rs), tenant.tenantId(), id).stream().findFirst();
    }

    @Override
    public void insert(TenantContext tenant, Candidate c) {
        jdbc.update("""
                INSERT INTO governance.lifecycle_access_approval_candidate(
                    id,tenant_id,identity_id,lifecycle_rule_id,target_kind,target_id,
                    policy_version_id,approval_plan_hash,correlation_id,causation_id,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,identity_id,lifecycle_rule_id,target_kind,target_id,policy_version_id,approval_plan_hash)
                DO NOTHING
                """, c.id(), tenant.tenantId(), c.identityId(), c.lifecycleRuleId(),
                c.targetKind().name(), c.targetId(), c.policyVersionId(), c.approvalPlanHash(),
                c.correlationId(), c.causationId(), Timestamp.from(c.createdAt()));
    }

    private static Candidate map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Candidate(
                rs.getObject("id",UUID.class), rs.getObject("identity_id",UUID.class),
                rs.getObject("lifecycle_rule_id",UUID.class),
                AccessAssignment.TargetKind.valueOf(rs.getString("target_kind")),
                rs.getObject("target_id",UUID.class), rs.getObject("policy_version_id",UUID.class),
                rs.getString("approval_plan_hash"), rs.getObject("correlation_id",UUID.class),
                rs.getObject("causation_id",UUID.class), rs.getTimestamp("created_at").toInstant());
    }
}
