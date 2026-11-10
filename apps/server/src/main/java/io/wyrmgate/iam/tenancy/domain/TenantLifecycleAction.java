package io.wyrmgate.iam.tenancy.domain;

/** Semantic actions that may change Tenant lifecycle. */
public enum TenantLifecycleAction {
    ACTIVATE,
    SUSPEND,
    RESUME,
    BEGIN_DECOMMISSIONING,
    COMPLETE_DECOMMISSIONING
}
