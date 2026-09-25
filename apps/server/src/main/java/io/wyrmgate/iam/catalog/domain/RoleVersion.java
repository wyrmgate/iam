package io.wyrmgate.iam.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable-on-activation composition snapshot for one Role. */
public record RoleVersion(
        UUID id,
        UUID roleId,
        long versionNumber,
        State state,
        String contentHash,
        Instant activatedAt,
        Instant createdAt,
        Instant updatedAt) {

    public RoleVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(state, "state");
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be positive");
        if ((state == State.ACTIVE || state == State.SUPERSEDED) && activatedAt == null) {
            throw new IllegalArgumentException("activated/superseded version requires activatedAt");
        }
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }

    public enum State {
        DRAFT,
        VALIDATING,
        READY,
        ACTIVE,
        SUPERSEDED,
        REJECTED,
        CANCELLED
    }
}
