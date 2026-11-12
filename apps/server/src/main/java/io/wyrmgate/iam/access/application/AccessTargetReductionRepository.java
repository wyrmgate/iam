package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessTargetReduction;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Access-owned persistence port for durable Catalog target reductions. */
public interface AccessTargetReductionRepository {

    AccessTargetReduction startIfAbsent(
            TenantContext tenant,
            AccessTargetReduction reduction);

    Optional<AccessTargetReduction> findById(
            TenantContext tenant,
            UUID reductionId);

    AccessTargetReduction recordPage(
            TenantContext tenant,
            UUID reductionId,
            Instant afterCreatedAt,
            UUID afterAssignmentId,
            long processedDelta,
            boolean completed,
            long expectedRevision,
            Instant now);
}
