package io.wyrmgate.iam.tenancy.application;

import io.wyrmgate.iam.tenancy.domain.TenantLifecycleState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenancy-owned bounded read contract for Tenant inventory and current metadata. */
public interface TenantOverviewQuery {

    Optional<TenantSummary> find(UUID tenantId);

    List<TenantSummary> listAfter(Instant createdAt, UUID id, int limit);

    record TenantSummary(
            UUID id,
            String displayName,
            TenantLifecycleState lifecycleState,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
    }
}
