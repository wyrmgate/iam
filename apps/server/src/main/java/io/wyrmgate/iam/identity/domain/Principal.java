package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned technical representation/account on one ApplicationTarget. */
public record Principal(
        UUID id,
        UUID identityId,
        UUID applicationTargetId,
        PrincipalKind kind,
        String nativePrincipalKey,
        PrincipalLifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public Principal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationTargetId, "applicationTargetId");
        Objects.requireNonNull(kind, "kind");
        nativePrincipalKey = requireText(nativePrincipalKey, "nativePrincipalKey", 512);
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public boolean correlated() {
        return identityId != null;
    }

    private static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(name + " exceeds maximum length");
        }
        return normalized;
    }
}
