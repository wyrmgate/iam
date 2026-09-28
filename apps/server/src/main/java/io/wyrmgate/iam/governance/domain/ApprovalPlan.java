package io.wyrmgate.iam.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApprovalPlan(
        UUID id,
        ApprovalSubject subject,
        LifecycleState lifecycleState,
        int currentStageOrdinal,
        Instant deadlineAt,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public ApprovalPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (currentStageOrdinal < 0) {
            throw new IllegalArgumentException("currentStageOrdinal must be non-negative");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (lifecycleState == LifecycleState.PENDING && completedAt != null) {
            throw new IllegalArgumentException("pending ApprovalPlan must not be completed");
        }
        if (lifecycleState != LifecycleState.PENDING && completedAt == null) {
            throw new IllegalArgumentException("terminal ApprovalPlan requires completedAt");
        }
    }

    public boolean isExpiredAt(Instant at) {
        return lifecycleState == LifecycleState.PENDING
                && deadlineAt != null
                && !at.isBefore(deadlineAt);
    }

    public enum LifecycleState {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED,
        SUPERSEDED
    }
}
