package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Semantic Installation query proving exact sealed bootstrap lineage for legacy owner adoption. */
@FunctionalInterface
public interface SealedInstallationBootstrapQuery {

    boolean matchesSealedInstallation(
            TenantContext tenant,
            UUID identityId,
            UUID principalId,
            UUID bootstrapCorrelationId);
}
