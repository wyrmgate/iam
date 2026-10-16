package io.wyrmgate.iam.installation;

import io.wyrmgate.iam.administration.application.SealedInstallationBootstrapQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Installation-owned semantic adapter used by Administration's explicit legacy adoption command. */
public final class InstallationSealedBootstrapQueryAdapter implements SealedInstallationBootstrapQuery {

    private final InstallationBootstrapRepository repository;

    public InstallationSealedBootstrapQueryAdapter(InstallationBootstrapRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public boolean matchesSealedInstallation(
            TenantContext tenant,
            UUID identityId,
            UUID principalId,
            UUID bootstrapCorrelationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(bootstrapCorrelationId, "bootstrapCorrelationId");
        InstallationBootstrapPlan plan = repository.find().orElse(null);
        return plan != null
                && plan.state() == InstallationBootstrapState.SEALED
                && plan.sealedAt() != null
                && plan.tenantId().equals(tenant.tenantId())
                && plan.identityId().equals(identityId)
                && plan.principalId().equals(principalId)
                && plan.correlationId().equals(bootstrapCorrelationId);
    }
}
