package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EffectiveAccessRepository {

    void applyDirectAssignment(
            TenantContext tenant,
            AccessAssignment assignment,
            String principalConstraintKey,
            String pathHash,
            Instant computedAt);

    void removeAssignmentSupport(
            TenantContext tenant,
            UUID assignmentId,
            Instant computedAt);

    Optional<EffectiveAccess> findCurrent(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey,
            Instant at);

    List<UUID> currentSupportingAssignmentIds(
            TenantContext tenant,
            UUID effectiveAccessId,
            Instant at);
}
