package io.wyrmgate.iam.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AdministrativeElevation(
        UUID id,
        UUID beneficiaryIdentityId,
        UUID initiatorIdentityId,
        UUID authorityBasisGrantId,
        UUID roleId,
        AdministrativeScope scope,
        Instant validFrom,
        Instant validUntil,
        String requestFingerprint,
        AdministrativeElevationState state,
        UUID approvalCaseId,
        String approvalPlanFingerprint,
        Instant activatedAt,
        Instant deniedAt,
        Instant cancelledAt,
        Instant revokedAt,
        UUID correlationId,
        UUID causationId,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AdministrativeElevation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(beneficiaryIdentityId, "beneficiaryIdentityId");
        Objects.requireNonNull(initiatorIdentityId, "initiatorIdentityId");
        Objects.requireNonNull(authorityBasisGrantId, "authorityBasisGrantId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (requestFingerprint.isBlank()) throw new IllegalArgumentException("requestFingerprint must not be blank");
        if (validFrom != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }

    public boolean isEffectiveAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        if (state != AdministrativeElevationState.ACTIVE) return false;
        if (validFrom != null && instant.isBefore(validFrom)) return false;
        return instant.isBefore(validUntil);
    }
}
