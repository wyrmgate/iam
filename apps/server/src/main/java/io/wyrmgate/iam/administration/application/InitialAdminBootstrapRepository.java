package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.administration.domain.PlatformOwnerAuthority;
import io.wyrmgate.iam.administration.domain.TenantOwnerAuthority;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Administration-owned mutation/query port used only by the first-admin bootstrap operation. */
public interface InitialAdminBootstrapRepository {

    Optional<InitialAdminBootstrap> findBootstrap(TenantContext tenant);

    boolean hasAnyAdministrativeGrant(TenantContext tenant);

    boolean isGrantEffective(
            TenantContext tenant,
            UUID grantId,
            UUID actorIdentityId,
            Instant at);

    boolean isTenantOwnerAuthorityEffective(
            TenantContext tenant,
            UUID grantId,
            UUID actorIdentityId,
            Instant at);

    boolean isPlatformOwnerAuthorityEffective(
            TenantContext actorTenant,
            UUID actorIdentityId,
            UUID actorPrincipalId,
            Instant at);

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

    void insertTenantOwnerAuthority(TenantContext tenant, TenantOwnerAuthority authority);

    void insertPlatformOwnerAuthority(PlatformOwnerAuthority authority);
}
