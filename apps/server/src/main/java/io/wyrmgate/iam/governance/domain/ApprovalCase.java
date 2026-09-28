package io.wyrmgate.iam.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApprovalCase(
        UUID id,
        SubjectType subjectType,
        UUID subjectId,
        long subjectRevision,
        UUID requesterIdentityId,
        LifecycleState lifecycleState,
        int currentStageOrdinal,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public ApprovalCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (subjectRevision < 1) {
            throw new IllegalArgumentException("subjectRevision must be positive");
        }
        if (currentStageOrdinal < 0) {
            throw new IllegalArgumentException("currentStageOrdinal must be non-negative");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if ((lifecycleState == LifecycleState.PENDING) != (completedAt == null)) {
            throw new IllegalArgumentException(
                    "only PENDING approval case may have no completedAt");
        }
    }

    public enum SubjectType {
        REQUEST_ITEM,
        ADMINISTRATIVE_ELEVATION,
        ROLE_VERSION_ACTIVATION,
        POLICY_VERSION_ACTIVATION,
        GOVERNANCE_EXCEPTION,
        CREDENTIAL_OPERATION
    }

    public enum LifecycleState {
        PENDING,
        APPROVED,
        REJECTED,
        CANCELLED,
        SUPERSEDED
    }
}
