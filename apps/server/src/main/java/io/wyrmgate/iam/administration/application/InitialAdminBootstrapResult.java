package io.wyrmgate.iam.administration.application;

import java.util.UUID;

public record InitialAdminBootstrapResult(
        UUID bootstrapId,
        UUID actorBindingId,
        UUID administrativeRoleId,
        UUID administrativeGrantId,
        UUID correlationId) {
}
