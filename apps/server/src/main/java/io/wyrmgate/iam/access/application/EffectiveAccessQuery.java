package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.EffectiveAccess;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EffectiveAccessQuery {

    Optional<Result> find(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey,
            Instant at);

    record Result(EffectiveAccess effectiveAccess, List<UUID> supportingAssignmentIds) {
        public Result {
            if (effectiveAccess == null) throw new IllegalArgumentException("effectiveAccess is required");
            supportingAssignmentIds = List.copyOf(supportingAssignmentIds);
            if (supportingAssignmentIds.isEmpty()) {
                throw new IllegalArgumentException("supportingAssignmentIds must not be empty");
            }
        }
    }
}
