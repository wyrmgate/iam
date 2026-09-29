package io.wyrmgate.iam.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ReviewModels {
    private ReviewModels() {}

    public enum CampaignKind { IDENTITY_ACCESS }
    public enum CampaignState { DRAFT, GENERATING, ACTIVE, COMPLETED, FAILED }
    public enum ItemState { PENDING, DECIDED }
    public enum DecisionValue { KEEP, REVOKE }
    public enum RemediationState {
        PENDING,
        APPLIED,
        NO_ACTION_REQUIRED,
        FAILED,
        MANUAL_REQUIRED
    }

    public record ReviewCampaign(
            UUID id,
            CampaignKind kind,
            UUID subjectIdentityId,
            UUID reviewerIdentityId,
            Instant snapshotAt,
            CampaignState state,
            Instant generationAfterCreatedAt,
            UUID generationAfterId,
            long generatedItemCount,
            long decidedItemCount,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant activatedAt,
            Instant completedAt,
            String failureCode) {
        public ReviewCampaign {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(subjectIdentityId, "subjectIdentityId");
            Objects.requireNonNull(reviewerIdentityId, "reviewerIdentityId");
            Objects.requireNonNull(snapshotAt, "snapshotAt");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (subjectIdentityId.equals(reviewerIdentityId)) {
                throw new IllegalArgumentException("reviewer must differ from subject Identity");
            }
            if ((generationAfterCreatedAt == null) != (generationAfterId == null)) {
                throw new IllegalArgumentException("generation continuation must be complete or absent");
            }
            if (generatedItemCount < 0 || decidedItemCount < 0
                    || decidedItemCount > generatedItemCount) {
                throw new IllegalArgumentException("invalid review campaign counts");
            }
            if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        }
    }

    public record ReviewItem(
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
            ItemState state,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    public record ReviewDecision(
            UUID id,
            UUID reviewItemId,
            UUID reviewerIdentityId,
            DecisionValue decision,
            String reason,
            Instant decidedAt) {}

    public record ReviewRemediation(
            UUID id,
            UUID reviewItemId,
            UUID accessAssignmentId,
            RemediationState state,
            String resultCode,
            String resultingAccessState,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {}
}
