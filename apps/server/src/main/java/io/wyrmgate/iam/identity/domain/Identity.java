package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Canonical governed subject and its required compatible typed profile. */
public record Identity(
        UUID id,
        IdentityType type,
        IdentityProfile profile,
        IdentityLifecycleState lifecycleState,
        String displayName,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public Identity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (profile.identityType() != type) {
            throw new IllegalArgumentException("profile must be compatible with identity type " + type);
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
