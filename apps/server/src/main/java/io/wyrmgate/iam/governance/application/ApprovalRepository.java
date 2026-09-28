package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRepository {

    void insertCase(TenantContext tenant, ApprovalCase approvalCase);

    void insertPlan(
            TenantContext tenant,
            UUID planId,
            UUID approvalCaseId,
            ApprovalPlanSpec.SelfApprovalPolicy selfApprovalPolicy,
            Instant createdAt);

    void insertStage(
            TenantContext tenant,
            UUID stageId,
            UUID planId,
            int stageOrdinal,
            ApprovalPlanSpec.DecisionMode decisionMode,
            Instant createdAt);

    void insertParticipant(
            TenantContext tenant,
            UUID participantId,
            UUID stageId,
            UUID identityId,
            Instant createdAt);

    Optional<ApprovalCase> findCase(
            TenantContext tenant, UUID approvalCaseId);

    Optional<ApprovalCase> findPendingCaseBySubject(
            TenantContext tenant,
            ApprovalCase.SubjectType subjectType,
            UUID subjectId);

    Optional<PlanSnapshot> findPlanByCase(
            TenantContext tenant, UUID approvalCaseId);

    Optional<StageSnapshot> findStage(
            TenantContext tenant,
            UUID approvalCaseId,
            int stageOrdinal);

    List<ApprovalDecision> findStageDecisions(
            TenantContext tenant,
            UUID approvalCaseId,
            UUID approvalStageId);

    Optional<ApprovalDecision> findDecision(
            TenantContext tenant,
            UUID approvalCaseId,
            UUID approvalStageId,
            UUID participantIdentityId);

    void insertDecision(
            TenantContext tenant, ApprovalDecision decision);

    ApprovalCase updateCase(
            TenantContext tenant,
            UUID approvalCaseId,
            long expectedRevision,
            ApprovalCase.LifecycleState lifecycleState,
            int currentStageOrdinal,
            Instant now,
            Instant completedAt);

    List<InboxItem> findInboxPage(
            TenantContext tenant,
            UUID participantIdentityId,
            Instant afterCreatedAt,
            UUID afterCaseId,
            int limit);

    record PlanSnapshot(
            UUID planId,
            ApprovalPlanSpec.SelfApprovalPolicy selfApprovalPolicy,
            int stageCount) {}

    record StageSnapshot(
            UUID stageId,
            int stageOrdinal,
            ApprovalPlanSpec.DecisionMode decisionMode,
            List<UUID> participantIdentityIds) {
        public StageSnapshot {
            participantIdentityIds = List.copyOf(participantIdentityIds);
        }
    }

    record InboxItem(
            ApprovalCase approvalCase,
            UUID stageId,
            ApprovalPlanSpec.DecisionMode decisionMode,
            List<UUID> participantIdentityIds) {
        public InboxItem {
            participantIdentityIds = List.copyOf(participantIdentityIds);
        }
    }
}
