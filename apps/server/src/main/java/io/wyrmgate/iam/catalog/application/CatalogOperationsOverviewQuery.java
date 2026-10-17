package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;

/** Catalog-owned bounded point-in-time projections used by first-party Console overviews. */
public interface CatalogOperationsOverviewQuery {

    record ApplicationOriginCount(String origin, long count) {}

    long activeApplications(TenantContext tenant);

    List<ApplicationOriginCount> activeApplicationOrigins(TenantContext tenant);
}
