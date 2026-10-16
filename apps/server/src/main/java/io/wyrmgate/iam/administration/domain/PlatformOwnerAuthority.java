package io.wyrmgate.iam.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Administration-owned deployment-scoped PLATFORM_OWNER authority. */
public record PlatformOwnerAuthority(
        UUID id,
        UUID actorTenantId,
        UUID actorIdentityId,
        UUID actorPrincipalId,
        OwnerAuthorityState state,
        boolean grantable,
        boolean delegable,
        UUID authorityBasisId,
        Instant validFrom,
        Instant validUntil,
        UUID revokedByIdentityId,
        Instant revokedAt,
        UUID correlationId,
        UUID causationId,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public PlatformOwnerAuthority {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actorTenantId, "actorTenantId");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
    }

    public boolean isEffectiveAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return state == OwnerAuthorityState.ACTIVE
                && (validFrom == null || !validFrom.isAfter(at))
                && (validUntil == null || validUntil.isAfter(at));
    }
}
