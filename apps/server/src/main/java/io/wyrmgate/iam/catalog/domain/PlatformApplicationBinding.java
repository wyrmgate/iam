package io.wyrmgate.iam.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Catalog-owned binding from one platform offering to one tenant-local Application. */
public record PlatformApplicationBinding(
        UUID id,
        UUID platformApplicationId,
        UUID tenantId,
        UUID applicationId,
        CatalogLifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public PlatformApplicationBinding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(platformApplicationId, "platformApplicationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
