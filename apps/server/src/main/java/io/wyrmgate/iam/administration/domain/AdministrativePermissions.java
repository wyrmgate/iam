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

    public static final Set<AdministrativePermission> INITIAL_TENANT_ADMIN = Set.of(
            MANAGE_AUTHORIZATION,
            IDENTITY_READ,
            IDENTITY_CREATE,
            IDENTITY_UPDATE);

    private AdministrativePermissions() {
    }
}
