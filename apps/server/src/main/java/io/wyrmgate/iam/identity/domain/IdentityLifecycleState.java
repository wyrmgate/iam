package io.wyrmgate.iam.identity.domain;

/** Business lifecycle of the governed Identity, independent of technical fulfillment. */
public enum IdentityLifecycleState {
    PENDING,
    ACTIVE,
    SUSPENDED,
    INACTIVE,
    DECOMMISSIONED
}
