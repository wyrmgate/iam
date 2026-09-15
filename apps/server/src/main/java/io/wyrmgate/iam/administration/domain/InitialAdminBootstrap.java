package io.wyrmgate.iam.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable burn-once marker and evidence pointer for a tenant's first administrative bootstrap. */
public record InitialAdminBootstrap(
        UUID id,
        UUID actorIdentityId,
        UUID actorBindingId,
        UUID administrativeRoleId,
        UUID administrativeGrantId,
        Instant completedAt,
        UUID correlationId) {

    public InitialAdminBootstrap {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(actorBindingId, "actorBindingId");
        Objects.requireNonNull(administrativeRoleId, "administrativeRoleId");
        Objects.requireNonNull(administrativeGrantId, "administrativeGrantId");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
