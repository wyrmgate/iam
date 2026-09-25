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
