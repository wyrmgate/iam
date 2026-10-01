package io.wyrmgate.iam.administration.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Administration-owned emergency authority and immutable activation evidence. */
public record AdministrativeBreakGlassOperation(
        UUID id,
        UUID actorIdentityId,
        UUID roleId,
        AdministrativeScope scope,
        String reason,
        String incidentReference,
        Instant validFrom,
        Instant validUntil,
        AuthenticationAssuranceLevel activationAssuranceLevel,
        Instant activationAuthenticatedAt,
        Instant activationStepUpAt,
        long maxAssuranceAgeSeconds,
        AdministrativeBreakGlassState state,
        Instant activatedAt,
        UUID revokedByIdentityId,
        Instant revokedAt,
        UUID correlationId,
        UUID causationId,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AdministrativeBreakGlassOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
        reason = bounded(reason, "reason", 2048);
        incidentReference = bounded(incidentReference, "incidentReference", 512);
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(activationAssuranceLevel, "activationAssuranceLevel");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(activatedAt, "activatedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (activationAssuranceLevel != AuthenticationAssuranceLevel.STRONG
                || activationStepUpAt == null) {
            throw new IllegalArgumentException(
                    "break-glass activation requires STRONG assurance evidence");
        }
        if (maxAssuranceAgeSeconds <= 0) {
            throw new IllegalArgumentException("maxAssuranceAgeSeconds must be positive");
        }
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if (state == AdministrativeBreakGlassState.ACTIVE
                && (revokedByIdentityId != null || revokedAt != null)) {
            throw new IllegalArgumentException("ACTIVE break-glass must not carry revocation evidence");
        }
        if (state == AdministrativeBreakGlassState.REVOKED
                && (revokedByIdentityId == null || revokedAt == null)) {
            throw new IllegalArgumentException("REVOKED break-glass requires revocation evidence");
        }
    }

    public boolean isEffectiveAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return state == AdministrativeBreakGlassState.ACTIVE
                && !now.isBefore(validFrom)
                && now.isBefore(validUntil);
    }

    public boolean currentAssuranceAllows(
            AuthenticationAssuranceContext assurance,
            Instant now) {
        Objects.requireNonNull(assurance, "assurance");
        return assurance.satisfiesStrongAt(
                now, Duration.ofSeconds(maxAssuranceAgeSeconds));
    }

    private static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(field + " must not exceed " + max + " characters");
        }
        return normalized;
    }
}
