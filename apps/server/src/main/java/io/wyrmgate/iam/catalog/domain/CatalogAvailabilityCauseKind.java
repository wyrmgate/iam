package io.wyrmgate.iam.catalog.domain;

/** Catalog resource kinds that can materially determine access-target availability. */
public enum CatalogAvailabilityCauseKind {
    APPLICATION,
    APPLICATION_TARGET,
    ENTITLEMENT,
    ROLE
}
