package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-time semantic guard that prevents stale Access projections from extending authority.
 */
public final class EffectiveAccessSemanticSupport {

    private final AccessAssignmentRepository assignments;
    private final CatalogAccessTargetAvailabilityQuery catalog;

    public EffectiveAccessSemanticSupport(
            AccessAssignmentRepository assignments,
            CatalogAccessTargetAvailabilityQuery catalog) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public boolean contributes(
            TenantContext tenant,
            UUID assignmentId,
            Instant at) {
        AccessAssignment assignment = assignments.findById(
                        tenant, assignmentId)
                .orElse(null);
        if (assignment == null || !assignment.isSemanticallyEffectiveAt(at)) {
            return false;
        }
        CatalogAccessTargetKind targetKind =
                CatalogAccessTargetKind.valueOf(
                        assignment.targetKind().name());
        UUID targetId = assignment.targetKind()
                == AccessAssignment.TargetKind.ROLE
                ? assignment.roleId()
                : assignment.entitlementId();
        return catalog.currentAvailability(tenant, targetKind, targetId)
                .map(decision -> decision.assignable())
                .orElse(false);
    }
}
