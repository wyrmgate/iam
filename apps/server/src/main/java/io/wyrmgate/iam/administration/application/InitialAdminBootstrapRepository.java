package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/** Administration-owned mutation port used only by the one-time first-admin bootstrap operation. */
public interface InitialAdminBootstrapRepository {

    boolean hasAnyAdministrativeGrant(TenantContext tenant);

    boolean claimBootstrap(TenantContext tenant, InitialAdminBootstrap bootstrap);

    UUID ensurePermission(
            TenantContext tenant,
            UUID proposedPermissionId,
            AdministrativePermission permission,
            Instant now);

    void insertRole(TenantContext tenant, AdministrativeRole role);

    void attachPermissions(
            TenantContext tenant,
            UUID roleId,
            Collection<UUID> permissionIds,
            Instant now);

    void insertGrant(TenantContext tenant, AdministrativeGrant grant);
}
