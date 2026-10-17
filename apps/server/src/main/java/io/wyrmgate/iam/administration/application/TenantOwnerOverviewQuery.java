package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Administration-owned read projection for current Tenant Owner authority and viability. */
public interface TenantOwnerOverviewQuery {

    record Owner(UUID identityId, boolean administrativelyEligible) {}

    record Snapshot(long effectiveOwnerCount, long viableOwnerCount, List<Owner> owners) {
        public Snapshot {
            owners = List.copyOf(owners);
        }
    }

    Snapshot snapshot(TenantContext tenant, Instant at, int limit);
}
