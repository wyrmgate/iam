package io.wyrmgate.iam.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Catalog-owned governed access package. */
public record Role(
        UUID id,
        RoleType type,
        UUID applicationId,
        String code,
        String name,
        CatalogLifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public Role {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        code = normalizeCode(code);
        name = normalizeName(name);
        if (type == RoleType.APPLICATION) {
            Objects.requireNonNull(applicationId, "applicationId");
        } else if (applicationId != null) {
            throw new IllegalArgumentException("BUSINESS role must not carry applicationId");
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }

    public static String normalizeCode(String value) {
        return requireText(value, "code", 128);
    }

    public static String normalizeName(String value) {
        return requireText(value, "name", 512);
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

    public enum RoleType {
        BUSINESS,
        APPLICATION
    }
}
