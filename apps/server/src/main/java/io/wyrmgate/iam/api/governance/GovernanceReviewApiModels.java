package io.wyrmgate.iam.api.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class GovernanceReviewApiModels {
    private GovernanceReviewApiModels() {}

    record ReviewCampaignResource(
            UUID id,
            String kind,
            UUID subjectIdentityId,
            UUID reviewerIdentityId,
            Instant snapshotAt,
            String state,
            long generatedItemCount,
            long decidedItemCount,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant activatedAt,
            Instant completedAt,
            String failureCode) {}

    record ReviewCampaignPage(
            List<ReviewCampaignResource> items,
            String nextCursor) {
        ReviewCampaignPage {
            items = List.copyOf(items);
        }
    }

    record ReviewDecisionResource(
            UUID id,
            UUID reviewerIdentityId,
            String decision,
            String reason,
            Instant decidedAt) {}

    record ReviewRemediationResource(
            UUID id,
            UUID reviewItemId,
            UUID accessAssignmentId,
            String state,
            String resultCode,
            String resultingAccessState,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {}

    record ReviewItemResource(
            UUID id,
            UUID reviewCampaignId,
            UUID reviewerIdentityId,
            UUID accessAssignmentId,
            long assignmentRevision,
            String targetKind,
            UUID roleId,
            UUID entitlementId,
            String principalConstraintKind,
            UUID specificPrincipalId,
            String provenanceKind,
            UUID provenanceRefId,
            String snapshotLifecycleState,
            Instant validFrom,
            Instant validUntil,
            Instant assignmentCreatedAt,
            Instant snapshotAt,
            String state,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            ReviewDecisionResource decision,
            ReviewRemediationResource remediation) {}

    record ReviewItemPage(
            List<ReviewItemResource> items,
            String nextCursor) {
        ReviewItemPage {
            items = List.copyOf(items);
        }
    }
}
