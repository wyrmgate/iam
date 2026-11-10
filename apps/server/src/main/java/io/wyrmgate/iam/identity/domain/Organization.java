package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Tenant-local business/governance structure owned by Identity. */
public record Organization(
        UUID id,
        String name,
        String organizationType,
        UUID parentId,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public Organization {
        Objects.requireNonNull(id, "id");
        name = requireText(name, "name", 512);
        organizationType = requireText(organizationType, "organizationType", 128);
        if (id.equals(parentId)) {
            throw new IllegalArgumentException("organization cannot be its own parent");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds maximum length");
        }
        return normalized;
    }
}
