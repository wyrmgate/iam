package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.InitialAdminAuthenticationAnchorType;
import java.util.UUID;

public record InitialAdminBootstrapResult(
        UUID bootstrapId,
        InitialAdminAuthenticationAnchorType authenticationAnchorType,
        UUID actorBindingId,
        UUID actorPrincipalId,
        UUID administrativeRoleId,
        UUID administrativeGrantId,
        UUID correlationId) {
}
