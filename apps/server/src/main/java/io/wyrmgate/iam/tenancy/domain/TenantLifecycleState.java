package io.wyrmgate.iam.tenancy.domain;

/** Authoritative Tenancy-owned lifecycle of a Tenant isolation realm. */
public enum TenantLifecycleState {
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    DECOMMISSIONING,
    DECOMMISSIONED
}
