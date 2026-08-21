package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned canonical Identity extension schema. Activated versions are immutable. */
public record CanonicalSchemaVersion(
        UUID id,
        long versionNumber,
        State state,
        Instant createdAt,
        Instant activatedAt,
        Instant supersededAt) {

    public enum State { DRAFT, ACTIVE, SUPERSEDED }

    public CanonicalSchemaVersion {
        Objects.requireNonNull(id, "id");
        if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be positive");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        if (state == State.DRAFT && (activatedAt != null || supersededAt != null)) {
            throw new IllegalArgumentException("draft schema must not have activation timestamps");
        }
        if (state == State.ACTIVE && (activatedAt == null || supersededAt != null)) {
            throw new IllegalArgumentException("active schema requires activatedAt only");
        }
        if (state == State.SUPERSEDED && (activatedAt == null || supersededAt == null || supersededAt.isBefore(activatedAt))) {
            throw new IllegalArgumentException("superseded schema requires ordered activation timestamps");
        }
    }
}
