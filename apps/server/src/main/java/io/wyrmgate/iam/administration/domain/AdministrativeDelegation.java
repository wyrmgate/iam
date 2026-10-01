package io.wyrmgate.iam.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Administration-owned, source-dependent single-hop delegated authority. */
public record AdministrativeDelegation(
        UUID id,
        UUID delegateIdentityId,
        UUID delegatorIdentityId,
        UUID sourceGrantId,
        UUID roleId,
        AdministrativeScope scope,
        AdministrativeDelegationState state,
        Instant validFrom,
        Instant validUntil,
        UUID createdByIdentityId,
        UUID revokedByIdentityId,
        Instant revokedAt,
        UUID correlationId,
        UUID causationId,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AdministrativeDelegation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(delegateIdentityId, "delegateIdentityId");
        Objects.requireNonNull(delegatorIdentityId, "delegatorIdentityId");
        Objects.requireNonNull(sourceGrantId, "sourceGrantId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(createdByIdentityId, "createdByIdentityId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (validFrom != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (state == AdministrativeDelegationState.REVOKED
                && (revokedByIdentityId == null || revokedAt == null)) {
            throw new IllegalArgumentException("revoked delegation requires revocation evidence");
        }
        if (state == AdministrativeDelegationState.ACTIVE
                && (revokedByIdentityId != null || revokedAt != null)) {
            throw new IllegalArgumentException("active delegation must not carry revocation evidence");
        }
    }

    public boolean isEffectiveAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        if (state != AdministrativeDelegationState.ACTIVE) {
            return false;
        }
        if (validFrom != null && instant.isBefore(validFrom)) {
            return false;
        }
        return instant.isBefore(validUntil);
    }
}
