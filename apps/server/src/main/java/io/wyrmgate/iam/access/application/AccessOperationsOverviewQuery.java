package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.UUID;

/** Access-owned bounded point-in-time projections used by first-party Console overviews. */
public interface AccessOperationsOverviewQuery {

    record EntitlementAccessCount(UUID entitlementId, long count) {}

    long activeAssignments(TenantContext tenant);

    List<EntitlementAccessCount> effectiveAccessByEntitlement(TenantContext tenant, int limit);
}
