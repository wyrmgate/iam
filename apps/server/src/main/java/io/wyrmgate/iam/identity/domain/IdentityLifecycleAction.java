package io.wyrmgate.iam.identity.domain;

/** Semantic actions that may change the authoritative Identity lifecycle. */
public enum IdentityLifecycleAction {
    ACTIVATE,
    SUSPEND,
    RESUME,
    DEACTIVATE,
    DECOMMISSION
}
