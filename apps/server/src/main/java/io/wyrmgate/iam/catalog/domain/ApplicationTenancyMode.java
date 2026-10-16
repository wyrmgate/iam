package io.wyrmgate.iam.catalog.domain;

/** Semantic ownership/tenancy mode for a Wyrmgate application definition. */
public enum ApplicationTenancyMode {
    TENANT_LOCAL,
    PLATFORM_SHARED,
    PLATFORM_INTERNAL
}
