package io.wyrmgate.iam.catalog.domain;

/** How a platform-owned Application offering admits tenant bindings. */
public enum PlatformApplicationAdmissionMode {
    ALL_TENANTS,
    SELECTED_TENANTS,
    GOVERNED_POLICY
}
