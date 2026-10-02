package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.EvidenceResourceReference;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Typed producer input for one immutable EvidenceSnapshot. */
public record EvidenceSnapshotDraft(
        UUID id,
        Instant occurredAt,
        UUID actorId,
        String snapshotType,
        EvidenceResourceReference subject,
        EvidenceResourceReference policy,
        EvidenceResourceReference related,
        String decisionLabel,
        UUID correlationId,
        UUID causationId) {

    public EvidenceSnapshotDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(subject, "subject");
        if (snapshotType == null || snapshotType.isBlank()) {
            throw new IllegalArgumentException("snapshotType must not be blank");
        }
        if (decisionLabel == null || decisionLabel.isBlank()) {
            throw new IllegalArgumentException("decisionLabel must not be blank");
        }
    }
}
