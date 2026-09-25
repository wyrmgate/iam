package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned semantic adapter for Access reference validation. */
public final class IdentityAccessReferenceQueryService
        implements IdentityAccessReferenceQuery {

    private final IdentityRepository identities;
    private final PrincipalRepository principals;

    public IdentityAccessReferenceQueryService(
            IdentityRepository identities,
            PrincipalRepository principals) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.principals = Objects.requireNonNull(principals, "principals");
    }

    @Override
    public boolean identityExists(TenantContext tenant, UUID identityId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        return identities.findById(tenant, identityId).isPresent();
    }

    @Override
    public PrincipalReference principal(
            TenantContext tenant, UUID principalId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        return principals.findById(tenant, principalId)
                .map(principal -> principal.identityId() == null
                        ? PrincipalReference.uncorrelated(
                                principal.applicationTargetId())
                        : PrincipalReference.resolved(
                                principal.identityId(),
                                principal.applicationTargetId()))
                .orElseGet(PrincipalReference::notFound);
    }
}
