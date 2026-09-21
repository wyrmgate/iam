package io.wyrmgate.iam.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Catalog-owned technical target/environment belonging to exactly one Application. */
public record ApplicationTarget(
        UUID id,
        UUID applicationId,
        String code,
        CatalogLifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public ApplicationTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationId, "applicationId");
        code = requireText(code, "code", 128);
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    private static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(name + " exceeds maximum length");
        return normalized;
    }
}
