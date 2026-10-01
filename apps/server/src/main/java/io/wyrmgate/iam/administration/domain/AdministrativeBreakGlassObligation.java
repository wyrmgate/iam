package io.wyrmgate.iam.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable Administration-owned work/evidence obligation created with emergency activation. */
public record AdministrativeBreakGlassObligation(
        UUID id,
        UUID breakGlassOperationId,
        AdministrativeBreakGlassObligationType type,
        AdministrativeBreakGlassObligationState state,
        Instant completedAt,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AdministrativeBreakGlassObligation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(breakGlassOperationId, "breakGlassOperationId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
        if (state == AdministrativeBreakGlassObligationState.PENDING && completedAt != null) {
            throw new IllegalArgumentException("PENDING obligation must not be completed");
        }
        if (state == AdministrativeBreakGlassObligationState.COMPLETED && completedAt == null) {
            throw new IllegalArgumentException("COMPLETED obligation requires completedAt");
        }
    }
}
