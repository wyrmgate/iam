package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.application.LifecycleAccessApprovalCommand.Context;
import io.wyrmgate.iam.access.application.LifecycleAccessPrivilegeGuard.MaterialContext;
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
            UUID policyVersionId, String approvalPlanHash, Context context) {
        return jdbc.query("""
                SELECT id, identity_id, lifecycle_rule_id, target_kind, target_id,
                       policy_version_id, approval_plan_hash, identity_access_epoch, lifecycle_policy_version_id, target_fingerprint, risk_fingerprint, correlation_id, causation_id, created_at
                FROM governance.lifecycle_access_approval_candidate
                WHERE tenant_id=? AND identity_id=? AND lifecycle_rule_id=? AND target_kind=?
                  AND target_id=? AND policy_version_id=? AND approval_plan_hash=? AND identity_access_epoch=? AND lifecycle_policy_version_id=? AND target_fingerprint=? AND risk_fingerprint=?
                """, (rs,n)->map(rs), tenant.tenantId(), identityId, lifecycleRuleId,
                targetKind.name(), targetId, policyVersionId, approvalPlanHash, context.identityEpoch(), context.lifecyclePolicyVersionId(), context.governance().targetFingerprint(), context.governance().riskFingerprint()).stream().findFirst();
    }

    @Override
    public Optional<Candidate> findById(TenantContext tenant, UUID id) {
        return jdbc.query("""
                SELECT id, identity_id, lifecycle_rule_id, target_kind, target_id,
                       policy_version_id, approval_plan_hash, identity_access_epoch, lifecycle_policy_version_id, target_fingerprint, risk_fingerprint, correlation_id, causation_id, created_at
                FROM governance.lifecycle_access_approval_candidate
                WHERE tenant_id=? AND id=?
                """, (rs,n)->map(rs), tenant.tenantId(), id).stream().findFirst();
    }

    @Override
    public void insert(TenantContext tenant, Candidate c) {
        jdbc.update("""
                INSERT INTO governance.lifecycle_access_approval_candidate(
                    id,tenant_id,identity_id,lifecycle_rule_id,target_kind,target_id,
                    policy_version_id,approval_plan_hash,identity_access_epoch,lifecycle_policy_version_id,target_fingerprint,risk_fingerprint,correlation_id,causation_id,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,identity_id,lifecycle_rule_id,target_kind,target_id,policy_version_id,approval_plan_hash,identity_access_epoch,lifecycle_policy_version_id,target_fingerprint,risk_fingerprint)
                DO NOTHING
                """, c.id(), tenant.tenantId(), c.identityId(), c.lifecycleRuleId(),
                c.targetKind().name(), c.targetId(), c.policyVersionId(), c.approvalPlanHash(),
                c.context().identityEpoch(), c.context().lifecyclePolicyVersionId(), c.context().governance().targetFingerprint(), c.context().governance().riskFingerprint(), c.correlationId(), c.causationId(), Timestamp.from(c.createdAt()));
    }

    private static Context context(java.sql.ResultSet rs) throws java.sql.SQLException {
        long epoch=rs.getLong("identity_access_epoch");
        if(rs.wasNull()) return null;
        return new Context(epoch,rs.getObject("lifecycle_policy_version_id",UUID.class),new MaterialContext(rs.getObject("policy_version_id",UUID.class),rs.getString("target_fingerprint"),rs.getString("risk_fingerprint")));
    }

    private static Candidate map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Candidate(
                rs.getObject("id",UUID.class), rs.getObject("identity_id",UUID.class),
                rs.getObject("lifecycle_rule_id",UUID.class),
                AccessAssignment.TargetKind.valueOf(rs.getString("target_kind")),
                rs.getObject("target_id",UUID.class), rs.getObject("policy_version_id",UUID.class),
                rs.getString("approval_plan_hash"), context(rs), rs.getObject("correlation_id",UUID.class),
                rs.getObject("causation_id",UUID.class), rs.getTimestamp("created_at").toInstant());
    }
}
