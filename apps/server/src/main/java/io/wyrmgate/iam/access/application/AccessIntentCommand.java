package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

public interface AccessIntentCommand {
    AccessAssignment applyApprovedRequest(
            TenantContext tenant,
            UUID identityId,
            AccessAssignment.TargetKind targetKind,
            UUID roleId,
            UUID entitlementId,
            AccessAssignment.PrincipalConstraintKind principalConstraintKind,
            UUID specificPrincipalId,
            UUID requestItemId,
            Instant validFrom,
            Instant validUntil,
            Instant now);
}
