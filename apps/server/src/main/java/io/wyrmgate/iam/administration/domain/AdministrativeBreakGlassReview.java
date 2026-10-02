package io.wyrmgate.iam.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable Administration-owned post-use review evidence for one break-glass operation. */
public record AdministrativeBreakGlassReview(
        UUID id,
        UUID breakGlassOperationId,
        UUID obligationId,
        UUID reviewerIdentityId,
        AdministrativeBreakGlassReviewOutcome outcome,
        String summary,
        Instant reviewedAt,
        UUID correlationId,
        UUID causationId,
        Instant createdAt) {

    public AdministrativeBreakGlassReview {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(breakGlassOperationId, "breakGlassOperationId");
        Objects.requireNonNull(obligationId, "obligationId");
        Objects.requireNonNull(reviewerIdentityId, "reviewerIdentityId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(reviewedAt, "reviewedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        if (summary.isBlank() || summary.length() > 2048) {
            throw new IllegalArgumentException("summary must be non-blank and at most 2048 characters");
        }
    }
}
