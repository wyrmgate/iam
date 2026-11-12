package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityPropagation;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Durable Catalog-owned continuation trigger for bounded target-availability propagation. */
public interface CatalogAvailabilityPropagationWorkSink {

    String PROPAGATION_REQUESTED =
            "catalog.access-target-availability-propagation-requested";

    void requested(
            TenantContext tenant,
            CatalogAvailabilityPropagation propagation,
            UUID correlationId,
            UUID causationId);
}
