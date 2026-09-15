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

    public static final Set<AdministrativePermission> INITIAL_TENANT_ADMIN = Set.of(
            MANAGE_AUTHORIZATION,
            IDENTITY_READ,
            IDENTITY_CREATE,
            IDENTITY_UPDATE);

    private AdministrativePermissions() {
    }
}
