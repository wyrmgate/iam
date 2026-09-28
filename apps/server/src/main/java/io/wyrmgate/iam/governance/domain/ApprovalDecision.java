package io.wyrmgate.iam.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApprovalDecision(
        UUID id,
        UUID approvalCaseId,
        UUID approvalStageId,
        UUID participantIdentityId,
        Decision decision,
        Instant decidedAt,
        UUID correlationId,
        UUID causationId) {

    public ApprovalDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(approvalCaseId, "approvalCaseId");
        Objects.requireNonNull(approvalStageId, "approvalStageId");
        Objects.requireNonNull(participantIdentityId, "participantIdentityId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    public enum Decision {
        APPROVE,
        REJECT
    }
}
