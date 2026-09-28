package io.wyrmgate.iam.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccessRequest(
        UUID id,
        UUID requesterIdentityId,
        UUID beneficiaryIdentityId,
        LifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public AccessRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requesterIdentityId, "requesterIdentityId");
        Objects.requireNonNull(beneficiaryIdentityId, "beneficiaryIdentityId");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if ((lifecycleState == LifecycleState.DRAFT
                || lifecycleState == LifecycleState.SUBMITTED)
                != (completedAt == null)) {
            throw new IllegalArgumentException(
                    "only unfinished AccessRequest may have no completedAt");
        }
    }

    public enum LifecycleState {
        DRAFT,
        SUBMITTED,
        COMPLETED,
        CANCELLED
    }
}
