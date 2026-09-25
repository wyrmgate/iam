package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.PrincipalLifecycleState;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PrincipalTechnicalReferenceQueryService
        implements PrincipalTechnicalReferenceQuery {

    private final PrincipalRepository principals;

    public PrincipalTechnicalReferenceQueryService(
            PrincipalRepository principals) {
        this.principals = Objects.requireNonNull(principals, "principals");
    }

    @Override
    public List<Result> activeForIdentityTarget(
            TenantContext tenant,
            UUID identityId,
            UUID applicationTargetId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(applicationTargetId, "applicationTargetId");
        return principals.findActiveByIdentityAndTarget(
                        tenant, identityId, applicationTargetId)
                .stream()
                .map(value -> Result.active(
                        value.id(),
                        value.identityId(),
                        value.applicationTargetId(),
                        value.nativePrincipalKey()))
                .toList();
    }

    @Override
    public Result resolve(TenantContext tenant, UUID principalId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        return principals.findById(tenant, principalId)
                .filter(value -> value.lifecycleState()
                        == PrincipalLifecycleState.ACTIVE)
                .filter(value -> value.identityId() != null)
                .map(value -> Result.active(
                        value.id(),
                        value.identityId(),
                        value.applicationTargetId(),
                        value.nativePrincipalKey()))
                .orElseGet(Result::unavailable);
    }
}
