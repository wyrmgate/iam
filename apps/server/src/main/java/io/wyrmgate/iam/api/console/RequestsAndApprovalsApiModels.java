package io.wyrmgate.iam.api.console;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RequestsAndApprovalsApiModels {
    private RequestsAndApprovalsApiModels() {}

    public record WindowResource(String preset, Instant from, Instant until) {}

    public record AttentionResource(
            long pendingApprovals,
            long draftRequests,
            long inFlightRequestItems,
            long appliedRequestItems) {}

    public record RequestStatusTotalsResource(
            long draft,
            long inProgress,
            long completedApplied,
            long completedMixed,
            long completedNoAccess) {}

    public record ApprovalWorkResource(
            UUID approvalCaseId,
            String subjectKind,
            UUID subjectId,
            UUID accessRequestId,
            String targetKind,
            UUID targetId,
            int currentStageOrdinal,
            Instant createdAt) {}

    public record RequestSummaryResource(
            UUID requestId,
            String presentationStatus,
            int itemCount,
            int draftItems,
            int submittedItems,
            int evaluatingItems,
            int pendingApprovalItems,
            int authorizedItems,
            int appliedItems,
            int deniedItems,
            int rejectedItems,
            int cancelledItems,
            int expiredItems,
            String singleTargetKind,
            UUID singleTargetId,
            Instant createdAt,
            Instant updatedAt) {}

    public record RequestsAndApprovalsResource(
            Instant asOf,
            UUID tenantId,
            UUID actorIdentityId,
            WindowResource window,
            String approvalSort,
            AttentionResource attention,
            RequestStatusTotalsResource requestStatusTotals,
            List<ApprovalWorkResource> approvals,
            List<RequestSummaryResource> requests) {
        public RequestsAndApprovalsResource {
            approvals = List.copyOf(approvals);
            requests = List.copyOf(requests);
        }
    }
}
