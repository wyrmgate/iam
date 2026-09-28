package io.wyrmgate.iam.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApprovalStage(
        UUID id,
        UUID approvalPlanId,
        int ordinal,
        DecisionMode decisionMode,
        LifecycleState lifecycleState,
        Instant createdAt,
        Instant updatedAt) {

    public ApprovalStage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(approvalPlanId, "approvalPlanId");
        Objects.requireNonNull(decisionMode, "decisionMode");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be non-negative");
        }
    }

    public enum DecisionMode {
        ANY_ONE,
        ALL
    }

    public enum LifecycleState {
        WAITING,
        ACTIVE,
        APPROVED,
        REJECTED,
        SKIPPED
    }
}
