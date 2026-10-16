package io.wyrmgate.iam.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Explicit TENANT_OWNER designation over one tenant-scoped direct AdministrativeGrant. */
public record TenantOwnerAuthority(
        UUID id,
        UUID actorIdentityId,
        UUID administrativeGrantId,
        OwnerAuthorityState state,
        Instant revokedAt,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public TenantOwnerAuthority {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(administrativeGrantId, "administrativeGrantId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if ((state == OwnerAuthorityState.ACTIVE) != (revokedAt == null)) {
            throw new IllegalArgumentException("owner authority revocation shape is invalid");
        }
    }
}
