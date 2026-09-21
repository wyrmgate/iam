package io.wyrmgate.iam.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Catalog-owned smallest governable technical access unit. */
public record Entitlement(
        UUID id,
        UUID applicationId,
        UUID applicationTargetId,
        String code,
        String nativeKey,
        String entitlementType,
        CatalogLifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public Entitlement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationId, "applicationId");
        code = requireText(code, "code", 256);
        nativeKey = optionalText(nativeKey, "nativeKey", 1024);
        entitlementType = requireText(entitlementType, "entitlementType", 64);
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

    private static String optionalText(String value, String name, int max) {
        if (value == null) return null;
        return requireText(value, name, max);
    }
}
