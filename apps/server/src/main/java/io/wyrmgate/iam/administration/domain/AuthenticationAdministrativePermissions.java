package io.wyrmgate.iam.administration.domain;

/** Finite semantic Administration permissions for Authentication/IdP/SSO control-plane resources. */
public final class AuthenticationAdministrativePermissions {

    public static final AdministrativePermission CLIENT_READ =
            new AdministrativePermission("authentication-client", "read");
    public static final AdministrativePermission CLIENT_CREATE =
            new AdministrativePermission("authentication-client", "create");
    public static final AdministrativePermission CLIENT_UPDATE =
            new AdministrativePermission("authentication-client", "update");
    public static final AdministrativePermission CLIENT_DISABLE =
            new AdministrativePermission("authentication-client", "disable");

    public static final AdministrativePermission LOGIN_BINDING_READ =
            new AdministrativePermission("authentication-login-binding", "read");
    public static final AdministrativePermission LOGIN_BINDING_CREATE =
            new AdministrativePermission("authentication-login-binding", "create");
    public static final AdministrativePermission LOGIN_BINDING_UPDATE =
            new AdministrativePermission("authentication-login-binding", "update");
    public static final AdministrativePermission LOGIN_BINDING_DISABLE =
            new AdministrativePermission("authentication-login-binding", "disable");

    public static final AdministrativePermission SESSION_READ =
            new AdministrativePermission("authentication-session", "read");
    public static final AdministrativePermission SESSION_REVOKE =
            new AdministrativePermission("authentication-session", "revoke");

    private AuthenticationAdministrativePermissions() {
    }
}