package io.wyrmgate.iam.platform.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only Platform query used by Console composition surfaces. */
public interface PlatformTenantOverviewQuery {
    Optional<TenantSummary> find(UUID tenantId);

    /**
     * Deterministic ascending collection read ordered by createdAt then id.
     * Null position values select the first page.
     */
    List<TenantSummary> listAfter(Instant createdAt, UUID id, int limit);

    record TenantSummary(UUID id, String displayName, long revision, Instant createdAt, Instant updatedAt) {}
}
