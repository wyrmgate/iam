package io.wyrmgate.iam.installation;

/** Durable deployment-level checkpoints for ADR-0044 fresh-install orchestration. */
public enum InstallationBootstrapState {
    PLANNED,
    TENANT_CREATED,
    IDENTITY_CREATED,
    CONSOLE_REGISTERED,
    PRINCIPAL_CREATED,
    CREDENTIAL_ENROLLED,
    ADMIN_BOOTSTRAPPED,
    VERIFIED,
    SEALED
}
