package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class AccessIntentCommandService implements AccessIntentCommand {

    private final AccessAssignmentCommandService assignments;

    public AccessIntentCommandService(
            AccessAssignmentCommandService assignments) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
    }

    @Override
    public AccessAssignment applyApprovedRequest(
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
            Instant now) {
        return targetKind == AccessAssignment.TargetKind.ROLE
                ? assignments.createApprovedRequestRoleAssignment(
                        tenant,
                        identityId,
                        roleId,
                        principalConstraintKind,
                        specificPrincipalId,
                        requestItemId,
                        validFrom,
                        validUntil,
                        now)
                : assignments.createApprovedRequestEntitlementAssignment(
                        tenant,
                        identityId,
                        entitlementId,
                        principalConstraintKind,
                        specificPrincipalId,
                        requestItemId,
                        validFrom,
                        validUntil,
                        now);
    }
}
