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
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (type == RoleType.APPLICATION) {
            Objects.requireNonNull(applicationId, "applicationId");
        } else if (applicationId != null) {
            throw new IllegalArgumentException("BUSINESS role must not carry applicationId");
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }

    public enum RoleType {
        BUSINESS,
        APPLICATION
    }
}
