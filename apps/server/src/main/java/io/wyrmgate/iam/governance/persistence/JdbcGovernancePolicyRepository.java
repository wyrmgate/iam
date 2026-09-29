package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.GovernancePolicyRepository;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.*;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcGovernancePolicyRepository
        implements GovernancePolicyRepository {

    private final JdbcTemplate jdbc;

    public JdbcGovernancePolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Policy> findPolicy(
            TenantContext tenant,
            PolicyKind kind) {
        return jdbc.query("""
                SELECT id, policy_kind, revision, created_at, updated_at
                FROM governance.policy
                WHERE tenant_id = ? AND policy_kind = ?
                """,
                (rs,row) -> new Policy(
                        rs.getObject("id", UUID.class),
                        PolicyKind.valueOf(rs.getString("policy_kind")),
                        rs.getLong("revision"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                tenant.tenantId(),
                kind.name())
                .stream()
                .findFirst();
    }

    @Override
    public void insertPolicy(
            TenantContext tenant,
            Policy policy) {
        jdbc.update("""
                INSERT INTO governance.policy (
                    id, tenant_id, policy_kind, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                policy.id(),
                tenant.tenantId(),
                policy.kind().name(),
                policy.revision(),
                Timestamp.from(policy.createdAt()),
                Timestamp.from(policy.updatedAt()));
    }

    @Override
    public long nextVersionNumber(
            TenantContext tenant,
            UUID policyId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(max(version_number), 0) + 1
                FROM governance.policy_version
                WHERE tenant_id = ? AND policy_id = ?
                """,
                Long.class,
                tenant.tenantId(),
                policyId);
        return value == null ? 1 : value;
    }

    @Override
    public void insertVersion(
            TenantContext tenant,
            PolicyVersion version) {
        jdbc.update("""
                INSERT INTO governance.policy_version (
                    id, tenant_id, policy_id, version_number, state,
                    default_decision, revision, created_at, updated_at,
                    activated_at, superseded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                version.id(),
                tenant.tenantId(),
                version.policyId(),
                version.versionNumber(),
                version.state().name(),
                version.defaultDecision().name(),
                version.revision(),
                Timestamp.from(version.createdAt()),
                Timestamp.from(version.updatedAt()),
                timestamp(version.activatedAt()),
                timestamp(version.supersededAt()));
    }

    @Override
    public void insertRule(
            TenantContext tenant,
            SoDRule rule) {
        jdbc.update("""
                INSERT INTO governance.sod_rule (
                    id, tenant_id, policy_version_id, rule_code,
                    left_entitlement_id, right_entitlement_id,
                    severity, enforcement_action, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rule.id(),
                tenant.tenantId(),
                rule.policyVersionId(),
                rule.code(),
                rule.leftEntitlementId(),
                rule.rightEntitlementId(),
                rule.severity().name(),
                rule.action().name(),
                Timestamp.from(rule.createdAt()));
    }

    @Override
    public void insertApprovalStage(
            TenantContext tenant,
            PolicyApprovalStage stage) {
        jdbc.update("""
                INSERT INTO governance.policy_approval_stage (
                    id, tenant_id, policy_version_id,
                    stage_ordinal, decision_mode, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                stage.id(),
                tenant.tenantId(),
                stage.policyVersionId(),
                stage.ordinal(),
                stage.decisionMode().name(),
                Timestamp.from(stage.createdAt()));
    }

    @Override
    public void insertApprovalApprover(
            TenantContext tenant,
            PolicyApprovalApprover approver) {
        jdbc.update("""
                INSERT INTO governance.policy_approval_approver (
                    id, tenant_id, policy_approval_stage_id,
                    approver_identity_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                approver.id(),
                tenant.tenantId(),
                approver.stageId(),
                approver.approverIdentityId(),
                Timestamp.from(approver.createdAt()));
    }

    @Override
    public Optional<PolicyVersion> findVersion(
            TenantContext tenant,
            UUID versionId) {
        return jdbc.query("""
                SELECT id, policy_id, version_number, state,
                       default_decision, revision, created_at, updated_at,
                       activated_at, superseded_at
                FROM governance.policy_version
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> versionRow(rs),
                tenant.tenantId(),
                versionId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<PolicyVersion> findActiveVersion(
            TenantContext tenant,
            PolicyKind kind) {
        return jdbc.query("""
                SELECT v.id, v.policy_id, v.version_number, v.state,
                       v.default_decision, v.revision,
                       v.created_at, v.updated_at,
                       v.activated_at, v.superseded_at
                FROM governance.policy_version v
                JOIN governance.policy p
                  ON p.tenant_id = v.tenant_id
                 AND p.id = v.policy_id
                WHERE v.tenant_id = ?
                  AND p.policy_kind = ?
                  AND v.state = 'ACTIVE'
                """,
                (rs,row) -> versionRow(rs),
                tenant.tenantId(),
                kind.name())
                .stream()
                .findFirst();
    }

    @Override
    public List<SoDRule> findRules(
            TenantContext tenant,
            UUID versionId) {
        return jdbc.query("""
                SELECT id, policy_version_id, rule_code,
                       left_entitlement_id, right_entitlement_id,
                       severity, enforcement_action, created_at
                FROM governance.sod_rule
                WHERE tenant_id = ? AND policy_version_id = ?
                ORDER BY rule_code, id
                """,
                (rs,row) -> new SoDRule(
                        rs.getObject("id", UUID.class),
                        rs.getObject("policy_version_id", UUID.class),
                        rs.getString("rule_code"),
                        rs.getObject("left_entitlement_id", UUID.class),
                        rs.getObject("right_entitlement_id", UUID.class),
                        RiskSeverity.valueOf(rs.getString("severity")),
                        SoDAction.valueOf(rs.getString("enforcement_action")),
                        rs.getTimestamp("created_at").toInstant()),
                tenant.tenantId(),
                versionId);
    }

    @Override
    public List<PolicyApprovalStage> findApprovalStages(
            TenantContext tenant,
            UUID versionId) {
        return jdbc.query("""
                SELECT id, policy_version_id, stage_ordinal,
                       decision_mode, created_at
                FROM governance.policy_approval_stage
                WHERE tenant_id = ? AND policy_version_id = ?
                ORDER BY stage_ordinal
                """,
                (rs,row) -> new PolicyApprovalStage(
                        rs.getObject("id", UUID.class),
                        rs.getObject("policy_version_id", UUID.class),
                        rs.getInt("stage_ordinal"),
                        io.wyrmgate.iam.governance.application
                                .ApprovalModels.DecisionMode.valueOf(
                                        rs.getString("decision_mode")),
                        rs.getTimestamp("created_at").toInstant()),
                tenant.tenantId(),
                versionId);
    }

    @Override
    public List<PolicyApprovalApprover> findApprovalApprovers(
            TenantContext tenant,
            UUID stageId) {
        return jdbc.query("""
                SELECT id, policy_approval_stage_id,
                       approver_identity_id, created_at
                FROM governance.policy_approval_approver
                WHERE tenant_id = ? AND policy_approval_stage_id = ?
                ORDER BY approver_identity_id
                """,
                (rs,row) -> new PolicyApprovalApprover(
                        rs.getObject("id", UUID.class),
                        rs.getObject("policy_approval_stage_id", UUID.class),
                        rs.getObject("approver_identity_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()),
                tenant.tenantId(),
                stageId);
    }

    @Override
    public PolicyVersion updateVersionState(
            TenantContext tenant,
            UUID versionId,
            VersionState state,
            long expectedRevision,
            Instant now,
            Instant activatedAt,
            Instant supersededAt) {
        int affected = jdbc.update("""
                UPDATE governance.policy_version
                SET state = ?,
                    revision = revision + 1,
                    updated_at = ?,
                    activated_at = ?,
                    superseded_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """,
                state.name(),
                Timestamp.from(now),
                timestamp(activatedAt),
                timestamp(supersededAt),
                tenant.tenantId(),
                versionId,
                expectedRevision);
        if (affected != 1) {
            if (findVersion(tenant, versionId).isEmpty()) {
                throw new IllegalArgumentException(
                        "policy version does not exist");
            }
            throw new StaleWriteException(
                    "policy-version",
                    versionId,
                    expectedRevision);
        }
        return findVersion(tenant, versionId).orElseThrow();
    }

    @Override
    public void insertRiskAssessment(
            TenantContext tenant,
            RiskAssessment assessment) {
        jdbc.update("""
                INSERT INTO governance.risk_assessment (
                    id, tenant_id, request_item_id, policy_version_id,
                    severity, factor_count, assessed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                assessment.id(),
                tenant.tenantId(),
                assessment.requestItemId(),
                assessment.policyVersionId(),
                assessment.severity().name(),
                assessment.factorCount(),
                Timestamp.from(assessment.assessedAt()));
    }

    @Override
    public void insertSoDConflict(
            TenantContext tenant,
            SoDConflict conflict) {
        jdbc.update("""
                INSERT INTO governance.sod_conflict (
                    id, tenant_id, risk_assessment_id, sod_rule_id,
                    requested_entitlement_id, conflicting_entitlement_id,
                    conflict_source, severity, enforcement_action, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                conflict.id(),
                tenant.tenantId(),
                conflict.riskAssessmentId(),
                conflict.sodRuleId(),
                conflict.requestedEntitlementId(),
                conflict.conflictingEntitlementId(),
                conflict.source().name(),
                conflict.severity().name(),
                conflict.action().name(),
                Timestamp.from(conflict.createdAt()));
    }

    @Override
    public void insertPolicyEvaluation(
            TenantContext tenant,
            PolicyEvaluation evaluation) {
        jdbc.update("""
                INSERT INTO governance.policy_evaluation (
                    id, tenant_id, access_request_id, request_item_id,
                    request_item_revision, policy_version_id,
                    risk_assessment_id, decision, evaluation_code, evaluated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                evaluation.id(),
                tenant.tenantId(),
                evaluation.accessRequestId(),
                evaluation.requestItemId(),
                evaluation.requestItemRevision(),
                evaluation.policyVersionId(),
                evaluation.riskAssessmentId(),
                evaluation.decision().name(),
                evaluation.code(),
                Timestamp.from(evaluation.evaluatedAt()));
    }

    private static PolicyVersion versionRow(
            java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new PolicyVersion(
                rs.getObject("id", UUID.class),
                rs.getObject("policy_id", UUID.class),
                rs.getLong("version_number"),
                VersionState.valueOf(rs.getString("state")),
                PolicyDecision.valueOf(
                        rs.getString("default_decision")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("activated_at")),
                instant(rs.getTimestamp("superseded_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
