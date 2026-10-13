package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Actor-scoped, read-only Governance projection for the Console /requests workbench. */
public interface RequestsAndApprovalsPageQuery {

    enum ApprovalSort { OLDEST, NEWEST }

    Snapshot snapshot(
            TenantContext tenant,
            UUID actorIdentityId,
            Instant from,
            Instant until,
            ApprovalSort approvalSort,
            int approvalLimit,
            int requestLimit);

    record Snapshot(
            long pendingApprovals,
            long draftRequests,
            long inFlightRequestItems,
            long appliedRequestItemsInWindow,
            RequestStatusTotals requestStatusTotals,
            List<ApprovalWork> approvalWork,
            List<RequestSummary> recentRequests) {
        public Snapshot {
            approvalWork = List.copyOf(approvalWork);
            recentRequests = List.copyOf(recentRequests);
        }
    }

    record RequestStatusTotals(
            long draft,
            long inProgress,
            long completedApplied,
            long completedMixed,
            long completedNoAccess) {}

    record ApprovalWork(
            UUID approvalCaseId,
            String subjectKind,
            UUID subjectId,
            UUID accessRequestId,
            String targetKind,
            UUID targetId,
            int currentStageOrdinal,
            Instant createdAt) {}

    record RequestSummary(
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
}
