package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Narrow Administration-owned state query port for ADR-0049 legacy owner adoption. */
public interface PlatformOwnerAdoptionRepository {

    long countPlatformOwnerAuthorities();

    long countMatchingActivePlatformOwner(TenantContext tenant, UUID identityId, UUID principalId);

    long countTenantOwnerAuthorities(TenantContext tenant);

    long countMatchingActiveTenantOwner(TenantContext tenant, UUID identityId, UUID administrativeGrantId);
}
