package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.governance.domain.ApprovalParticipant;
import io.wyrmgate.iam.governance.domain.ApprovalPlan;
import io.wyrmgate.iam.governance.domain.ApprovalStage;
import io.wyrmgate.iam.governance.domain.ApprovalSubject;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRepository {

    void insertPlan(TenantContext tenant, ApprovalPlan plan);

    void insertStage(TenantContext tenant, ApprovalStage stage);

    void insertParticipant(
            TenantContext tenant,
            ApprovalParticipant participant);

    Optional<ApprovalPlan> findPlan(
            TenantContext tenant, UUID planId);

    Optional<ApprovalPlan> findPendingBySubject(
            TenantContext tenant, ApprovalSubject subject);

    List<ApprovalStage> findStages(
            TenantContext tenant, UUID planId);

    Optional<ApprovalStage> findStage(
            TenantContext tenant, UUID planId, int ordinal);

    List<ApprovalParticipant> findParticipants(
            TenantContext tenant, UUID stageId);

    Optional<ApprovalDecision> findDecision(
            TenantContext tenant,
            UUID stageId,
            UUID approverIdentityId);

    List<ApprovalDecision> findDecisions(
            TenantContext tenant, UUID stageId);

    void insertDecision(
            TenantContext tenant, ApprovalDecision decision);

    void updateStageState(
            TenantContext tenant,
            UUID stageId,
            ApprovalStage.LifecycleState state,
            Instant now);

    ApprovalPlan updatePlan(
            TenantContext tenant,
            UUID planId,
            ApprovalPlan.LifecycleState state,
            int currentStageOrdinal,
            long expectedRevision,
            Instant now,
            Instant completedAt);

    void skipStagesAfter(
            TenantContext tenant,
            UUID planId,
            int ordinal,
            Instant now);
}
