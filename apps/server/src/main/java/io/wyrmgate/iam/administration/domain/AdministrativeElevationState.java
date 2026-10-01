package io.wyrmgate.iam.administration.domain;

public enum AdministrativeElevationState {
    REQUESTED,
    PENDING_APPROVAL,
    ACTIVE,
    DENIED,
    CANCELLED,
    REVOKED
}
