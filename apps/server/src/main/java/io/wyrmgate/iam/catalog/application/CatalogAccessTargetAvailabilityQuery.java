package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetAvailability;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;

/** Catalog-owned current-state semantic availability query required by ADR-0052. */
public interface CatalogAccessTargetAvailabilityQuery {

    Optional<CatalogAccessTargetAvailability> currentAvailability(
            TenantContext tenant,
            CatalogAccessTargetKind targetKind,
            UUID targetId);
}
