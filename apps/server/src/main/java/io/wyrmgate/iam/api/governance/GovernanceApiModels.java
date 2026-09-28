package io.wyrmgate.iam.api.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class GovernanceApiModels {
    private GovernanceApiModels() {}

    record RequestItemResource(
            UUID id,
            UUID accessRequestId,
            String targetKind,
            UUID roleId,
            UUID entitlementId,
            String principalConstraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil,
            String state,
            UUID approvalCaseId,
            UUID accessAssignmentId,
            String evaluationCode,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record AccessRequestResource(
            UUID id,
            UUID requesterIdentityId,
            UUID beneficiaryIdentityId,
            String state,
            long revision,
            Instant createdAt,
            Instant submittedAt,
            Instant updatedAt,
            List<RequestItemResource> items) {}

    record ApprovalInboxItem(
            UUID id,
            String subjectKind,
            UUID subjectId,
            String state,
            int currentStageOrdinal,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record ApprovalInboxPage(
            List<ApprovalInboxItem> items,
            String nextCursor) {}

    record ApprovalApproverResource(
            UUID approverIdentityId,
            Instant createdAt) {}

    record ApprovalDecisionResource(
            UUID id,
            UUID approverIdentityId,
            String decision,
            String reason,
            Instant decidedAt) {}

    record ApprovalStageResource(
            UUID id,
            int ordinal,
            String decisionMode,
            Instant createdAt,
            List<ApprovalApproverResource> approvers,
            List<ApprovalDecisionResource> decisions) {}

    record ApprovalEvidenceResource(
            UUID id,
            String subjectKind,
            UUID subjectId,
            UUID initiatorIdentityId,
            String state,
            int currentStageOrdinal,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            UUID approvalPlanId,
            long planNumber,
            String contentHash,
            Instant planCreatedAt,
            List<ApprovalStageResource> stages) {}

    record FieldError(
            String field,
            String code,
            String message) {}

    record ErrorResponse(
            String code,
            String message,
            UUID correlationId,
            List<FieldError> fieldErrors) {}
}
