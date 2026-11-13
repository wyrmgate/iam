package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Read-time semantic guard that prevents stale Access projections from extending authority. */
public final class EffectiveAccessSemanticSupport {

    private final AccessAssignmentRepository assignments;
    private final IdentityAccessReferenceQuery identities;
    private final CatalogAccessReferenceQuery catalogReferences;
    private final CatalogAccessTargetAvailabilityQuery catalog;
    private final RoleExpansionQuery roleExpansion;

    public EffectiveAccessSemanticSupport(
            AccessAssignmentRepository assignments,
            IdentityAccessReferenceQuery identities,
            CatalogAccessReferenceQuery catalogReferences,
            CatalogAccessTargetAvailabilityQuery catalog,
            RoleExpansionQuery roleExpansion) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.catalogReferences = Objects.requireNonNull(catalogReferences, "catalogReferences");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.roleExpansion = Objects.requireNonNull(roleExpansion, "roleExpansion");
    }

    /** Compatibility constructor for focused tests that do not model foreign dependency changes. */
    public EffectiveAccessSemanticSupport(
            AccessAssignmentRepository assignments,
            CatalogAccessTargetAvailabilityQuery catalog,
            RoleExpansionQuery roleExpansion) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.identities = null;
        this.catalogReferences = null;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.roleExpansion = Objects.requireNonNull(roleExpansion, "roleExpansion");
    }

    public boolean contributes(
            TenantContext tenant,
            UUID assignmentId,
            UUID entitlementId,
            Instant at) {
        AccessAssignment assignment = assignments.findById(tenant, assignmentId).orElse(null);
        if (assignment == null || !assignment.isSemanticallyEffectiveAt(at)) return false;
        if (!identityEpochCompatible(tenant, assignment)) return false;

        CatalogAccessTargetKind targetKind =
                CatalogAccessTargetKind.valueOf(assignment.targetKind().name());
        UUID targetId = assignment.targetKind() == AccessAssignment.TargetKind.ROLE
                ? assignment.roleId() : assignment.entitlementId();
        boolean assignable = catalog.currentAvailability(tenant, targetKind, targetId)
                .map(decision -> decision.assignable()).orElse(false);
        if (!assignable) return false;

        if (assignment.targetKind() == AccessAssignment.TargetKind.ENTITLEMENT) {
            return assignment.entitlementId().equals(entitlementId)
                    && specificDirectCompatible(tenant, assignment);
        }

        RoleExpansionQuery.Result current =
                roleExpansion.expandCurrent(tenant, assignment.roleId());
        return current.status() == RoleExpansionQuery.Status.AVAILABLE
                && specificRoleCompatible(tenant, assignment, current)
                && current.paths().stream()
                        .anyMatch(path -> path.entitlementId().equals(entitlementId));
    }

    private boolean identityEpochCompatible(
            TenantContext tenant, AccessAssignment assignment) {
        if (identities == null) return true;
        var status = identities.accessStatus(tenant, assignment.identityId());
        return status.status() == IdentityAccessReferenceQuery.AccessStatus.ACCESS_ELIGIBLE
                && status.accessEligibilityEpoch() == assignment.identityAccessEpoch();
    }

    private boolean specificDirectCompatible(
            TenantContext tenant, AccessAssignment assignment) {
        if (assignment.principalConstraintKind()
                != AccessAssignment.PrincipalConstraintKind.SPECIFIC) return true;
        if (identities == null || catalogReferences == null) return true;

        var principal = identities.principal(tenant, assignment.specificPrincipalId());
        if (principal.status() != IdentityAccessReferenceQuery.Status.RESOLVED
                || !assignment.identityId().equals(principal.identityId())) return false;

        var entitlement = catalogReferences.resolveActiveEntitlement(
                tenant, assignment.entitlementId());
        return entitlement.status() == CatalogAccessReferenceQuery.Status.VALID
                && entitlement.applicationTargetId().equals(principal.applicationTargetId());
    }

    private boolean specificRoleCompatible(
            TenantContext tenant,
            AccessAssignment assignment,
            RoleExpansionQuery.Result expansion) {
        if (assignment.principalConstraintKind()
                != AccessAssignment.PrincipalConstraintKind.SPECIFIC) return true;
        if (identities == null) return true;

        var principal = identities.principal(tenant, assignment.specificPrincipalId());
        if (principal.status() != IdentityAccessReferenceQuery.Status.RESOLVED
                || !assignment.identityId().equals(principal.identityId())) return false;
        var targets = expansion.paths().stream()
                .map(RoleExpansionQuery.EntitlementPath::applicationTargetId)
                .distinct().toList();
        return targets.size() == 1
                && targets.getFirst().equals(principal.applicationTargetId());
    }
}
