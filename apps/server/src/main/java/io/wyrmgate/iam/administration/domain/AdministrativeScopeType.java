package io.wyrmgate.iam.administration.domain;

/** Canonical strongly typed Administrative Authorization scope kinds. */
public enum AdministrativeScopeType {
    GLOBAL,
    ORGANIZATION,
    APPLICATION,
    APPLICATION_TARGET,
    SOURCE_SYSTEM,
    CONNECTOR_INSTANCE,
    IDENTITY_POPULATION,
    SPECIFIC_RESOURCE
}
