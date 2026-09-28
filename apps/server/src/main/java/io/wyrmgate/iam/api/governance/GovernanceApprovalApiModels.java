package io.wyrmgate.iam.api.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class GovernanceApprovalApiModels {
    private GovernanceApprovalApiModels() {}

    record ApprovalCaseResource(
            UUID id,
            String subjectType,
            UUID subjectId,
            long subjectRevision,
            UUID requesterIdentityId,
            String lifecycleState,
            int currentStageOrdinal,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {}

    record ApprovalInboxItemResource(
            ApprovalCaseResource approvalCase,
            UUID stageId,
            String decisionMode,
            List<UUID> participantIdentityIds) {}

    record ApprovalInboxPage(
            List<ApprovalInboxItemResource> items,
            String nextCursor) {}

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
