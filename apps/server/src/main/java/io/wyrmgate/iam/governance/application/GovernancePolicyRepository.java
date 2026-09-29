package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GovernancePolicyRepository {
    Optional<Policy> findPolicy(TenantContext tenant, PolicyKind kind);
    void insertPolicy(TenantContext tenant, Policy policy);
    long nextVersionNumber(TenantContext tenant, UUID policyId);
    void insertVersion(TenantContext tenant, PolicyVersion version);
    void insertRule(TenantContext tenant, SoDRule rule);
    void insertApprovalStage(TenantContext tenant, PolicyApprovalStage stage);
    void insertApprovalApprover(TenantContext tenant, PolicyApprovalApprover approver);
    Optional<PolicyVersion> findVersion(TenantContext tenant, UUID versionId);
    Optional<PolicyVersion> findActiveVersion(TenantContext tenant, PolicyKind kind);
    List<SoDRule> findRules(TenantContext tenant, UUID versionId);
    Optional<SoDRule> findRule(TenantContext tenant, UUID ruleId);
    List<PolicyApprovalStage> findApprovalStages(TenantContext tenant, UUID versionId);
    List<PolicyApprovalApprover> findApprovalApprovers(TenantContext tenant, UUID stageId);
    PolicyVersion updateVersionState(
            TenantContext tenant,
            UUID versionId,
            VersionState state,
            long expectedRevision,
            Instant now,
            Instant activatedAt,
            Instant supersededAt);
    void insertRiskAssessment(TenantContext tenant, RiskAssessment assessment);
    void insertSoDConflict(TenantContext tenant, SoDConflict conflict);
    void insertPolicyEvaluation(TenantContext tenant, PolicyEvaluation evaluation);
}
