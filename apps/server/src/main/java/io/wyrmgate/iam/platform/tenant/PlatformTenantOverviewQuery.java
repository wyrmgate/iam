package io.wyrmgate.iam.platform.tenant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Read-only Platform query used by Console composition surfaces. */
public interface PlatformTenantOverviewQuery {
    Optional<TenantSummary> find(UUID tenantId);

    record TenantSummary(UUID id, String displayName, long revision, Instant createdAt, Instant updatedAt) {}
}
