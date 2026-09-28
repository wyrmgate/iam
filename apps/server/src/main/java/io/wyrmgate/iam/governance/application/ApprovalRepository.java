package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalApprover;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalDecision;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalPlan;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalStage;
import io.wyrmgate.iam.governance.application.ApprovalModels.CaseState;
import io.wyrmgate.iam.governance.application.ApprovalModels.SubjectKind;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRepository {

    void insertCase(TenantContext tenant, ApprovalCase approvalCase);

    void insertPlan(TenantContext tenant, ApprovalPlan plan);

    void insertStage(TenantContext tenant, ApprovalStage stage);

    void insertApprover(TenantContext tenant, ApprovalApprover approver);

    void insertDecision(TenantContext tenant, ApprovalDecision decision);

    Optional<ApprovalCase> findCase(TenantContext tenant, UUID caseId);

    Optional<ApprovalCase> findPendingBySubject(
            TenantContext tenant, SubjectKind subjectKind, UUID subjectId);

    Optional<ApprovalCase> findLatestBySubject(
            TenantContext tenant, SubjectKind subjectKind, UUID subjectId);

    ApprovalPlan findPlan(TenantContext tenant, UUID caseId);

    List<ApprovalStage> findStages(TenantContext tenant, UUID planId);

    List<ApprovalApprover> findApprovers(TenantContext tenant, UUID stageId);

    Optional<ApprovalDecision> findDecision(
            TenantContext tenant, UUID stageId, UUID approverIdentityId);

    List<ApprovalDecision> findDecisions(TenantContext tenant, UUID stageId);

    List<ApprovalCase> findInbox(
            TenantContext tenant,
            UUID approverIdentityId,
            Instant afterCreatedAt,
            UUID afterId,
            int limit);

    boolean isParticipant(
            TenantContext tenant,
            UUID caseId,
            UUID identityId);

    ApprovalCase updateCase(
            TenantContext tenant,
            UUID caseId,
            CaseState state,
            int currentStageOrdinal,
            long expectedRevision,
            Instant now,
            Instant completedAt);
}
