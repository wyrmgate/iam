package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable evidence for one explicit Identity merge. */
public record IdentityMergeOperation(
        UUID id,
        UUID survivorIdentityId,
        UUID absorbedIdentityId,
        long survivorRevisionBefore,
        long absorbedRevisionBefore,
        int movedLinkCount,
        int movedPrincipalCount,
        String reason,
        UUID correlationId,
        UUID causationId,
        Instant completedAt) {

    public IdentityMergeOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(survivorIdentityId, "survivorIdentityId");
        Objects.requireNonNull(absorbedIdentityId, "absorbedIdentityId");
        if (survivorIdentityId.equals(absorbedIdentityId)) {
            throw new IllegalArgumentException("merge requires two distinct Identities");
        }
        if (survivorRevisionBefore < 1 || absorbedRevisionBefore < 1) {
            throw new IllegalArgumentException("merge revisions must be positive");
        }
        if (movedLinkCount < 0 || movedPrincipalCount < 0) {
            throw new IllegalArgumentException("merge move counts must not be negative");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("merge reason must not be blank");
        }
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
