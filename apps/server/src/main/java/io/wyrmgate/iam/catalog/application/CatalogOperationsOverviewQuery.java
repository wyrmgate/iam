package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Catalog-owned bounded point-in-time projections used by first-party Console overviews. */
public interface CatalogOperationsOverviewQuery {

    record ApplicationOriginCount(String origin, long count) {}

    record ApplicationAccessLabel(UUID applicationId, String displayName) {}

    long activeApplications(TenantContext tenant);

    List<ApplicationOriginCount> activeApplicationOrigins(TenantContext tenant);

    Map<UUID, ApplicationAccessLabel> applicationsForEntitlements(TenantContext tenant, Set<UUID> entitlementIds);
}
