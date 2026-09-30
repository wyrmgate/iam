package io.wyrmgate.iam.administration.domain;

import java.util.Set;

/** Stable semantic permissions introduced by implemented control-plane surfaces. */
public final class AdministrativePermissions {

    public static final AdministrativePermission MANAGE_AUTHORIZATION =
            new AdministrativePermission("administration", "manage-authorization");
    public static final AdministrativePermission IDENTITY_READ =
            new AdministrativePermission("identity", "read");
    public static final AdministrativePermission IDENTITY_CREATE =
            new AdministrativePermission("identity", "create");
    public static final AdministrativePermission IDENTITY_UPDATE =
            new AdministrativePermission("identity", "update");
    public static final AdministrativePermission IDENTITY_ACTIVATE =
            new AdministrativePermission("identity", "activate");
    public static final AdministrativePermission IDENTITY_SUSPEND =
            new AdministrativePermission("identity", "suspend");
    public static final AdministrativePermission IDENTITY_DEACTIVATE =
            new AdministrativePermission("identity", "deactivate");
    public static final AdministrativePermission IDENTITY_DECOMMISSION =
            new AdministrativePermission("identity", "decommission");
    public static final AdministrativePermission IDENTITY_MERGE =
            new AdministrativePermission("identity", "merge");
    public static final AdministrativePermission IDENTITY_SPLIT =
            new AdministrativePermission("identity", "split");

    public static final AdministrativePermission PRINCIPAL_READ =
            new AdministrativePermission("principal", "read");
    public static final AdministrativePermission PRINCIPAL_REGISTER =
            new AdministrativePermission("principal", "register");
    public static final AdministrativePermission PRINCIPAL_CORRELATE =
            new AdministrativePermission("principal", "correlate");


    public static final AdministrativePermission APPLICATION_READ =
            new AdministrativePermission("application", "read");
    public static final AdministrativePermission APPLICATION_CREATE =
            new AdministrativePermission("application", "create");
    public static final AdministrativePermission APPLICATION_UPDATE =
            new AdministrativePermission("application", "update");
    public static final AdministrativePermission APPLICATION_RETIRE =
            new AdministrativePermission("application", "retire");

    public static final AdministrativePermission APPLICATION_TARGET_READ =
            new AdministrativePermission("application-target", "read");
    public static final AdministrativePermission APPLICATION_TARGET_CREATE =
            new AdministrativePermission("application-target", "create");
    public static final AdministrativePermission APPLICATION_TARGET_RETIRE =
            new AdministrativePermission("application-target", "retire");

    public static final AdministrativePermission ENTITLEMENT_READ =
            new AdministrativePermission("entitlement", "read");
    public static final AdministrativePermission ENTITLEMENT_CREATE =
            new AdministrativePermission("entitlement", "create");
    public static final AdministrativePermission ENTITLEMENT_RETIRE =
            new AdministrativePermission("entitlement", "retire");

    public static final AdministrativePermission ROLE_READ =
            new AdministrativePermission("role", "read");
    public static final AdministrativePermission ROLE_CREATE =
            new AdministrativePermission("role", "create");
    public static final AdministrativePermission ROLE_UPDATE =
            new AdministrativePermission("role", "update");
    public static final AdministrativePermission ROLE_RETIRE =
            new AdministrativePermission("role", "retire");

    public static final AdministrativePermission ROLE_VERSION_READ =
            new AdministrativePermission("role-version", "read");
    public static final AdministrativePermission ROLE_VERSION_CREATE =
            new AdministrativePermission("role-version", "create");
    public static final AdministrativePermission ROLE_VERSION_VALIDATE =
            new AdministrativePermission("role-version", "validate");
    public static final AdministrativePermission ROLE_VERSION_ACTIVATE =
            new AdministrativePermission("role-version", "activate");

    public static final AdministrativePermission ACCESS_ASSIGNMENT_READ =
            new AdministrativePermission("access-assignment", "read");
    public static final AdministrativePermission ACCESS_ASSIGNMENT_CREATE =
            new AdministrativePermission("access-assignment", "create");
    public static final AdministrativePermission ACCESS_ASSIGNMENT_SUSPEND =
            new AdministrativePermission("access-assignment", "suspend");
    public static final AdministrativePermission ACCESS_ASSIGNMENT_RESUME =
            new AdministrativePermission("access-assignment", "resume");
    public static final AdministrativePermission ACCESS_ASSIGNMENT_CANCEL =
            new AdministrativePermission("access-assignment", "cancel");
    public static final AdministrativePermission ACCESS_ASSIGNMENT_REVOKE =
            new AdministrativePermission("access-assignment", "revoke");
    public static final AdministrativePermission EFFECTIVE_ACCESS_READ =
            new AdministrativePermission("effective-access", "read");

    public static final AdministrativePermission CREDENTIAL_READ =
            new AdministrativePermission("credential", "read");
    public static final AdministrativePermission CREDENTIAL_CREATE =
            new AdministrativePermission("credential", "create");
    public static final AdministrativePermission CREDENTIAL_REVOKE =
            new AdministrativePermission("credential", "revoke");
    public static final AdministrativePermission CREDENTIAL_COMPROMISE =
            new AdministrativePermission("credential", "compromise");
    public static final AdministrativePermission CREDENTIAL_ROTATE =
            new AdministrativePermission("credential", "rotate");
    public static final AdministrativePermission CREDENTIAL_ROTATION_READ =
            new AdministrativePermission("credential-rotation", "read");

    public static final AdministrativePermission REVIEW_CAMPAIGN_READ =
            new AdministrativePermission("review-campaign", "read");
    public static final AdministrativePermission REVIEW_CAMPAIGN_CREATE =
            new AdministrativePermission("review-campaign", "create");
    public static final AdministrativePermission REVIEW_CAMPAIGN_START =
            new AdministrativePermission("review-campaign", "start");

    public static final AdministrativePermission CONNECTOR_READ =
            new AdministrativePermission("connector", "read");
    public static final AdministrativePermission CONNECTOR_CREATE =
            new AdministrativePermission("connector", "create");
    public static final AdministrativePermission CONNECTOR_UPDATE =
            new AdministrativePermission("connector", "update");
    public static final AdministrativePermission CONNECTOR_DISABLE =
            new AdministrativePermission("connector", "disable");

    public static final AdministrativePermission CONNECTOR_BINDING_READ =
            new AdministrativePermission("connector-binding", "read");
    public static final AdministrativePermission CONNECTOR_BINDING_CREATE =
            new AdministrativePermission("connector-binding", "create");
    public static final AdministrativePermission CONNECTOR_BINDING_UPDATE =
            new AdministrativePermission("connector-binding", "update");
    public static final AdministrativePermission CONNECTOR_BINDING_DISABLE =
            new AdministrativePermission("connector-binding", "disable");

    public static final AdministrativePermission CONNECTOR_WORKER_READ =
            new AdministrativePermission("connector-worker", "read");
    public static final AdministrativePermission CONNECTOR_WORKER_CREATE =
            new AdministrativePermission("connector-worker", "create");
    public static final AdministrativePermission CONNECTOR_WORKER_UPDATE =
            new AdministrativePermission("connector-worker", "update");
    public static final AdministrativePermission CONNECTOR_WORKER_DISABLE =
            new AdministrativePermission("connector-worker", "disable");

    public static final AdministrativePermission ENTITLEMENT_OBSERVATION_MAPPING_READ =
            new AdministrativePermission("entitlement-observation-mapping", "read");
    public static final AdministrativePermission ENTITLEMENT_OBSERVATION_MAPPING_CREATE =
            new AdministrativePermission("entitlement-observation-mapping", "create");
    public static final AdministrativePermission ENTITLEMENT_OBSERVATION_MAPPING_RETIRE =
            new AdministrativePermission("entitlement-observation-mapping", "retire");

    public static final Set<AdministrativePermission> INITIAL_TENANT_ADMIN = Set.of(
            MANAGE_AUTHORIZATION,
            IDENTITY_READ,
            IDENTITY_CREATE,
            IDENTITY_UPDATE);

    private AdministrativePermissions() {
    }
}
