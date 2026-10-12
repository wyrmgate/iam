package io.wyrmgate.iam.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable burn-once marker and evidence pointer for a tenant's first administrative bootstrap. */
public record InitialAdminBootstrap(
        UUID id,
        UUID actorIdentityId,
        InitialAdminAuthenticationAnchorType authenticationAnchorType,
        UUID actorBindingId,
        UUID actorPrincipalId,
        UUID administrativeRoleId,
        UUID administrativeGrantId,
        Instant completedAt,
        UUID correlationId) {

    public InitialAdminBootstrap {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(authenticationAnchorType, "authenticationAnchorType");
        Objects.requireNonNull(administrativeRoleId, "administrativeRoleId");
        Objects.requireNonNull(administrativeGrantId, "administrativeGrantId");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(correlationId, "correlationId");

        switch (authenticationAnchorType) {
            case EXTERNAL_SUBJECT -> {
                Objects.requireNonNull(actorBindingId, "actorBindingId");
                if (actorPrincipalId != null) {
                    throw new IllegalArgumentException("actorPrincipalId must be absent for EXTERNAL_SUBJECT bootstrap");
                }
            }
            case FIRST_PARTY_PRINCIPAL -> {
                Objects.requireNonNull(actorPrincipalId, "actorPrincipalId");
                if (actorBindingId != null) {
                    throw new IllegalArgumentException("actorBindingId must be absent for FIRST_PARTY_PRINCIPAL bootstrap");
                }
            }
        }
    }
}
