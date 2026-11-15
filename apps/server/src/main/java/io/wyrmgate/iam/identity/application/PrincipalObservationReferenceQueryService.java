package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Authoritative Principal reference validation without exposing Identity persistence. */
public final class PrincipalObservationReferenceQueryService
        implements PrincipalObservationReferenceQuery {

    private final PrincipalRepository principals;

    public PrincipalObservationReferenceQueryService(PrincipalRepository principals) {
        this.principals = Objects.requireNonNull(principals, "principals");
    }

    @Override
    public java.util.List<UUID> principalIds(TenantContext tenant,UUID identityId,UUID after,int limit){
        if(limit<1 || limit>101) throw new IllegalArgumentException("principal observation page limit");
        return principals.listByIdentityAfterId(tenant,identityId,after,limit).stream().map(p->p.id()).toList();
    }

    @Override
    public Validation validate(
            TenantContext tenant,
            UUID principalId,
            UUID applicationTargetId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(applicationTargetId, "applicationTargetId");
        return principals.findById(tenant, principalId)
                .map(principal -> applicationTargetId.equals(principal.applicationTargetId())
                        ? Validation.VALID
                        : Validation.TARGET_MISMATCH)
                .orElse(Validation.NOT_FOUND);
    }
}
