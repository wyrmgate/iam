package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Narrow Administration-owned persistence port for ADR-0047 compatibility recovery. */
public interface StandaloneTenantOwnerRecoveryRepository {

    long countGrantsForActor(TenantContext tenant, UUID actorIdentityId);

    long countGrantsForRole(TenantContext tenant, UUID roleId);

    long countDelegationsForRole(TenantContext tenant, UUID roleId);

    long countElevationsForRole(TenantContext tenant, UUID roleId);

    long countBreakGlassOperationsForRole(TenantContext tenant, UUID roleId);

    void upgradeLegacyRolePermissions(
            TenantContext tenant,
            UUID roleId,
            long expectedRevision,
            Set<AdministrativePermission> expectedLegacyPermissions,
            Set<AdministrativePermission> targetPermissions,
            Instant now);
}
