package io.wyrmgate.iam.administration.domain;

/** Stable default-deny permissions for Integration operational status reads. */
public final class IntegrationOperationalPermissions {
    public static final AdministrativePermission PROVISIONING_JOB_READ =
            new AdministrativePermission("provisioning-job", "read");
    public static final AdministrativePermission PROVISIONING_TASK_READ =
            new AdministrativePermission("provisioning-task", "read");
    public static final AdministrativePermission PROVISIONING_ATTEMPT_READ =
            new AdministrativePermission("provisioning-attempt", "read");
    public static final AdministrativePermission RECONCILIATION_RUN_READ =
            new AdministrativePermission("reconciliation-run", "read");

    private IntegrationOperationalPermissions() {
    }
}
