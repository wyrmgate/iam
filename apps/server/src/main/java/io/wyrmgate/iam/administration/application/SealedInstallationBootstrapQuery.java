package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Semantic Installation query deriving the exact sealed bootstrap lineage for legacy owner adoption. */
@FunctionalInterface
public interface SealedInstallationBootstrapQuery {

    Optional<Candidate> findSealedInstallation();

    record Candidate(
            TenantContext tenant,
            UUID identityId,
            UUID principalId,
            UUID bootstrapCorrelationId) {
        public Candidate {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(identityId, "identityId");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(bootstrapCorrelationId, "bootstrapCorrelationId");
        }
    }
}
