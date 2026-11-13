package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class DesiredProvisioningStateQueryService
        implements DesiredProvisioningStateQuery {

    private final DesiredStateProjectionRepository repository;

    public DesiredProvisioningStateQueryService(
            DesiredStateProjectionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public TechnicalGrantDemand technicalGrantDemand(
            TenantContext tenant,
            UUID applicationTargetId,
            UUID entitlementId,
            UUID principalId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(applicationTargetId, "applicationTargetId");
        Objects.requireNonNull(entitlementId, "entitlementId");
        Objects.requireNonNull(principalId, "principalId");
        try {
            return repository.hasPresentTechnicalGrant(
                            tenant,
                            applicationTargetId,
                            entitlementId,
                            principalId)
                    ? TechnicalGrantDemand.REQUIRED
                    : TechnicalGrantDemand.NOT_REQUIRED;
        } catch (RuntimeException unavailable) {
            return TechnicalGrantDemand.UNAVAILABLE;
        }
    }

    @Override
    public PrincipalResult desiredPrincipal(
            TenantContext tenant, UUID desiredPrincipalId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(desiredPrincipalId, "desiredPrincipalId");
        try {
            return repository.findPrincipalById(tenant, desiredPrincipalId)
                    .map(PrincipalResult::current)
                    .orElseGet(PrincipalResult::absent);
        } catch (RuntimeException unavailable) {
            return PrincipalResult.unavailable();
        }
    }

    @Override
    public Result desiredGrant(TenantContext tenant, UUID desiredGrantId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(desiredGrantId, "desiredGrantId");
        try {
            return repository.findGrantById(tenant, desiredGrantId)
                    .map(Result::current)
                    .orElseGet(Result::absent);
        } catch (RuntimeException unavailable) {
            return Result.unavailable();
        }
    }
}
