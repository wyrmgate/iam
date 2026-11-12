package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityPropagation;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Catalog-owned persistence/query port for bounded retirement propagation. */
public interface CatalogAvailabilityPropagationRepository {

    CatalogAvailabilityPropagation startIfAbsent(
            TenantContext tenant,
            CatalogAvailabilityPropagation propagation);

    Optional<CatalogAvailabilityPropagation> findById(
            TenantContext tenant, UUID propagationId);

    List<ResourceRef> findPhasePage(
            TenantContext tenant,
            CatalogAvailabilityPropagation propagation,
            int limit);

    CatalogAvailabilityPropagation recordProgress(
            TenantContext tenant,
            UUID propagationId,
            CatalogAvailabilityPropagation.Phase nextPhase,
            Instant afterCreatedAt,
            UUID afterResourceId,
            long processedDelta,
            boolean completed,
            long expectedRevision,
            Instant now);

    record ResourceRef(UUID id, long revision, Instant createdAt) {
        public ResourceRef {
            if (id == null || revision < 1 || createdAt == null) {
                throw new IllegalArgumentException("invalid propagation resource reference");
            }
        }
    }
}
