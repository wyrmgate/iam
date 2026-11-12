package io.wyrmgate.iam.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Catalog-owned governable business application/capability. */
public record Application(
        UUID id,
        String code,
        String name,
        CatalogLifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public Application {
        Objects.requireNonNull(id, "id");
        code = normalizeCode(code);
        name = normalizeName(name);
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public static String normalizeCode(String value) {
        return requireText(value, "code", 128);
    }

    public static String normalizeName(String value) {
        return requireText(value, "name", 512);
    }

    private static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(name + " exceeds maximum length");
        return normalized;
    }
}
