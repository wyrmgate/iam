package io.wyrmgate.iam.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Administration-owned binding from an authenticated external subject to one governed actor. */
public record ControlPlaneActorBinding(
        UUID id,
        UUID tenantId,
        ExternalAuthenticationSubject externalSubject,
        UUID actorIdentityId,
        ControlPlaneActorBindingState state,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public ControlPlaneActorBinding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(externalSubject, "externalSubject");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(state, "state");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
