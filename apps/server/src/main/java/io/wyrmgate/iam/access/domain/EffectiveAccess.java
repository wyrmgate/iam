package io.wyrmgate.iam.access.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Access-owned rebuildable entitlement-level access projection. */
public record EffectiveAccess(
        UUID id,
        UUID identityId,
        UUID entitlementId,
        String principalConstraintKey,
        int supportCount,
        Instant computedAt,
        long projectionGeneration) {

    public EffectiveAccess {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(entitlementId, "entitlementId");
        if (principalConstraintKey == null || principalConstraintKey.isBlank()) {
            throw new IllegalArgumentException("principalConstraintKey must not be blank");
        }
        Objects.requireNonNull(computedAt, "computedAt");
        if (supportCount < 1) throw new IllegalArgumentException("supportCount must be positive");
        if (projectionGeneration < 1) {
            throw new IllegalArgumentException("projectionGeneration must be positive");
        }
    }
}
