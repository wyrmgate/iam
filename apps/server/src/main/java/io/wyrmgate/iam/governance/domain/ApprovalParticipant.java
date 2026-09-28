package io.wyrmgate.iam.governance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApprovalParticipant(
        UUID id,
        UUID approvalStageId,
        UUID approverIdentityId,
        int ordinal,
        Instant createdAt) {

    public ApprovalParticipant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(approvalStageId, "approvalStageId");
        Objects.requireNonNull(approverIdentityId, "approverIdentityId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be non-negative");
        }
    }
}
