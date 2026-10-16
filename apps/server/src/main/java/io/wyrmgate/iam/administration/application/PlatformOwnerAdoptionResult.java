package io.wyrmgate.iam.administration.application;

import java.util.UUID;

public record PlatformOwnerAdoptionResult(
        Status status,
        UUID actorIdentityId,
        UUID tenantOwnerAuthorityId,
        UUID platformOwnerAuthorityId) {

    public enum Status {
        ADOPTED,
        ALREADY_COMPLETE
    }
}
