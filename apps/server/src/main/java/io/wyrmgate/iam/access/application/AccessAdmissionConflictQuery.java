package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Access-owned complete bounded query of authoritative overlapping intent, not a projection. */
@FunctionalInterface
public interface AccessAdmissionConflictQuery {
    Set<UUID> overlappingEntitlementIds(TenantContext tenant, UUID identityId,
            Set<UUID> entitlementIds, Instant fromInclusive, Instant untilExclusive);
}
