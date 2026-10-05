package io.wyrmgate.iam.authentication.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Authentication-owned tenant-local login alias for an existing governed Principal/Identity. */
public record AuthenticationLoginBinding(
        UUID id,
        UUID principalId,
        UUID identityId,
        String loginIdentifier,
        String normalizedLoginIdentifier,
        LifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AuthenticationLoginBinding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(identityId, "identityId");
        loginIdentifier = requireIdentifier(loginIdentifier);
        normalizedLoginIdentifier = requireIdentifier(normalizedLoginIdentifier);
        if (!normalize(loginIdentifier).equals(normalizedLoginIdentifier)) {
            throw new IllegalArgumentException("normalizedLoginIdentifier must be derived from loginIdentifier");
        }
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }

    public enum LifecycleState { ACTIVE, DISABLED }

    public static String normalize(String value) {
        return requireIdentifier(value).toLowerCase(Locale.ROOT);
    }

    private static String requireIdentifier(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("loginIdentifier must not be blank");
        String normalized = value.trim();
        if (normalized.length() > 320) throw new IllegalArgumentException("loginIdentifier exceeds maximum length");
        return normalized;
    }
}