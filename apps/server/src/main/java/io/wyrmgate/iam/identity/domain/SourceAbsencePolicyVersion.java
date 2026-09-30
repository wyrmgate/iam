package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable Identity-owned policy enabling bounded absence inference for one SourceSystem. */
public record SourceAbsencePolicyVersion(
        UUID id,
        UUID sourceSystemId,
        long versionNumber,
        int maxInferredTransitions,
        State state,
        Instant createdAt,
        Instant activatedAt,
        Instant supersededAt) {

    public enum State {
        ACTIVE,
        SUPERSEDED
    }

    public SourceAbsencePolicyVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        if (maxInferredTransitions < 1) {
            throw new IllegalArgumentException("maxInferredTransitions must be positive");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(activatedAt, "activatedAt");
        if (state == State.ACTIVE && supersededAt != null) {
            throw new IllegalArgumentException("active policy must not be superseded");
        }
        if (state == State.SUPERSEDED
                && (supersededAt == null || supersededAt.isBefore(activatedAt))) {
            throw new IllegalArgumentException("superseded policy requires ordered timestamps");
        }
    }
}
