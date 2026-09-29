package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.IdentityAccessReduction;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Access-owned persistence port for durable Identity lifecycle access reduction. */
public interface IdentityAccessReductionRepository {

    IdentityAccessReduction startIfAbsent(
            TenantContext tenant,
            IdentityAccessReduction reduction);

    Optional<IdentityAccessReduction> findById(
            TenantContext tenant,
            UUID reductionId);

    Optional<IdentityAccessReduction> findBySource(
            TenantContext tenant,
            UUID identityId,
            long sourceIdentityRevision);

    IdentityAccessReduction recordPage(
            TenantContext tenant,
            UUID reductionId,
            Instant afterCreatedAt,
            UUID afterAssignmentId,
            long processedDelta,
            boolean completed,
            long expectedRevision,
            Instant now);
}
