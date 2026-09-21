package io.wyrmgate.iam.catalog.domain;

/** Business lifecycle for first-slice Catalog resources. Retirement is terminal. */
public enum CatalogLifecycleState {
    ACTIVE,
    RETIRED
}
