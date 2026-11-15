package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reads only Access-owned assignment rows; foreign meaning comes from semantic ports. */
public final class AccessAdmissionConflictQueryService implements AccessAdmissionConflictQuery {
    private static final int PAGE_SIZE = 200;
    private static final int MAX_ASSIGNMENTS = 1000;
    private static final int MAX_PATHS = 10000;
    private final AccessAssignmentRepository assignments;
    private final IdentityAccessReferenceQuery identities;
    private final CatalogAccessTargetAvailabilityQuery catalog;
    private final RoleExpansionQuery roles;
    private final EffectiveAccessSemanticSupport semantics;

    public AccessAdmissionConflictQueryService(AccessAssignmentRepository assignments,
            IdentityAccessReferenceQuery identities, CatalogAccessTargetAvailabilityQuery catalog,
            RoleExpansionQuery roles, EffectiveAccessSemanticSupport semantics) {
        this.assignments = Objects.requireNonNull(assignments);
        this.identities = Objects.requireNonNull(identities);
        this.catalog = Objects.requireNonNull(catalog);
        this.roles = Objects.requireNonNull(roles);
        this.semantics = Objects.requireNonNull(semantics);
    }

    @Override
    public Set<UUID> overlappingEntitlementIds(TenantContext tenant, UUID identityId,
            Set<UUID> entitlementIds, Instant from, Instant until) {
        Objects.requireNonNull(from);
        if (until != null && !until.isAfter(from)) return Set.of();
        if (entitlementIds.isEmpty()) return Set.of();
        if (entitlementIds.size() > 2000) throw unavailable();
        var identity = identities.accessStatus(tenant, identityId);
        if (identity.status() != IdentityAccessReferenceQuery.AccessStatus.ACCESS_ELIGIBLE) return Set.of();
        Set<UUID> result = new LinkedHashSet<>();
        Instant afterAt = null;
        UUID afterId = null;
        int scanned = 0, paths = 0;
        while (true) {
            var page = assignments.findAdmissionPage(tenant, identityId, identity.accessEligibilityEpoch(),
                    entitlementIds, from, until, afterAt, afterId, PAGE_SIZE);
            if (page.isEmpty()) break;
            for (AccessAssignment assignment : page) {
                if (++scanned > MAX_ASSIGNMENTS) throw unavailable();
                UUID target = assignment.targetKind() == AccessAssignment.TargetKind.ROLE
                        ? assignment.roleId() : assignment.entitlementId();
                var availability = catalog.currentAvailability(tenant,
                        CatalogAccessTargetKind.valueOf(assignment.targetKind().name()), target)
                        .orElseThrow(AccessAdmissionConflictQueryService::unavailable);
                if (availability.terminal()) continue;
                if (!availability.assignable()) throw unavailable();
                if (assignment.targetKind() == AccessAssignment.TargetKind.ENTITLEMENT) {
                    if (semantics.specificDirectCompatible(tenant, assignment)) result.add(assignment.entitlementId());
                } else {
                    var expansion = roles.expandCurrent(tenant, assignment.roleId());
                    if (expansion.status() != RoleExpansionQuery.Status.AVAILABLE || expansion.paths().isEmpty())
                        throw unavailable();
                    paths += expansion.paths().size();
                    if (paths > MAX_PATHS) throw unavailable();
                    if (semantics.specificRoleCompatible(tenant, assignment, expansion)) {
                        for (var path : expansion.paths())
                            if (entitlementIds.contains(path.entitlementId())) result.add(path.entitlementId());
                    }
                }
            }
            if (result.containsAll(entitlementIds) || page.size() < PAGE_SIZE) break;
            var last = page.getLast();
            afterAt = last.createdAt();
            afterId = last.id();
        }
        return Set.copyOf(result);
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("bounded authoritative admission context unavailable");
    }
}
