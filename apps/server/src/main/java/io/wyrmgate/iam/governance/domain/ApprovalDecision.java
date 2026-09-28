package io.wyrmgate.iam.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApprovalDecision(
        UUID id,
        UUID approvalPlanId,
        UUID approvalStageId,
        UUID approverIdentityId,
        Value value,
        String reason,
        UUID correlationId,
        UUID causationId,
        Instant decidedAt) {

    public ApprovalDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(approvalPlanId, "approvalPlanId");
        Objects.requireNonNull(approvalStageId, "approvalStageId");
        Objects.requireNonNull(approverIdentityId, "approverIdentityId");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(decidedAt, "decidedAt");
        if (reason != null && reason.isBlank()) {
            throw new IllegalArgumentException("reason must be null or non-blank");
        }
    }

    public enum Value {
        APPROVE,
        REJECT
    }
}
