package io.wyrmgate.iam.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Tenant-scoped authoritative direct Administrative Authorization grant. */
public record AdministrativeGrant(
        UUID id,
        UUID actorIdentityId,
        UUID roleId,
        AdministrativeScope scope,
        AdministrativeGrantState state,
        Instant validFrom,
        Instant validUntil,
        boolean grantable,
        boolean delegable,
        UUID authorityBasisGrantId,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AdministrativeGrant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(state, "state");
        if (authorityBasisGrantId != null && authorityBasisGrantId.equals(id)) {
            throw new IllegalArgumentException("authorityBasisGrantId must not reference the same grant");
        }
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
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

    /** Compatibility constructor for pre-grantability callers and persisted legacy grants. */
    public AdministrativeGrant(
            UUID id,
            UUID actorIdentityId,
            UUID roleId,
            AdministrativeScope scope,
            AdministrativeGrantState state,
            Instant validFrom,
            Instant validUntil,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                actorIdentityId,
                roleId,
                scope,
                state,
                validFrom,
                validUntil,
                false,
                false,
                null,
                revision,
                createdAt,
                updatedAt);
    }

    public boolean isEffectiveAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        if (state != AdministrativeGrantState.ACTIVE) {
            return false;
        }
        if (validFrom != null && instant.isBefore(validFrom)) {
            return false;
        }
        return validUntil == null || instant.isBefore(validUntil);
    }
}
