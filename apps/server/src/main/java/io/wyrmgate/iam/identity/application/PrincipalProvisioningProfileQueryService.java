package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class PrincipalProvisioningProfileQueryService
        implements PrincipalProvisioningProfileQuery {

    private final IdentityRepository identities;

    public PrincipalProvisioningProfileQueryService(IdentityRepository identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    @Override
    public Result resolve(TenantContext tenant, UUID identityId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        try {
            return identities.findById(tenant, identityId)
                    .map(identity -> Result.available(identity.id(), identity.displayName()))
                    .orElseGet(Result::unavailable);
        } catch (RuntimeException unavailable) {
            return Result.unavailable();
        }
    }
}
