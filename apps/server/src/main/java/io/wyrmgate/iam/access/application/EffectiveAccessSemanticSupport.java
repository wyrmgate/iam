package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
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
    private final RoleExpansionQuery roleExpansion;

    public EffectiveAccessSemanticSupport(
            AccessAssignmentRepository assignments,
            CatalogAccessTargetAvailabilityQuery catalog,
            RoleExpansionQuery roleExpansion) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.roleExpansion = Objects.requireNonNull(roleExpansion, "roleExpansion");
    }

    public boolean contributes(
            TenantContext tenant,
            UUID assignmentId,
            UUID entitlementId,
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
        boolean assignable = catalog.currentAvailability(
                        tenant, targetKind, targetId)
                .map(decision -> decision.assignable())
                .orElse(false);
        if (!assignable) {
            return false;
        }
        if (assignment.targetKind() == AccessAssignment.TargetKind.ENTITLEMENT) {
            return assignment.entitlementId().equals(entitlementId);
        }
        RoleExpansionQuery.Result current = roleExpansion.expandCurrent(
                tenant, assignment.roleId());
        return current.status() == RoleExpansionQuery.Status.AVAILABLE
                && current.paths().stream()
                        .anyMatch(path -> path.entitlementId().equals(entitlementId));
    }
}
