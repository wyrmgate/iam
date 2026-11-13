package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.catalog.domain.RoleCardinality;
import io.wyrmgate.iam.catalog.domain.RoleVersion;
import io.wyrmgate.iam.catalog.domain.RoleVersionMember;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RoleExpansionQueryService implements RoleExpansionQuery {

    private final CatalogRepository catalog;
    private final RoleRepository roles;

    public RoleExpansionQueryService(
            CatalogRepository catalog,
            RoleRepository roles) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    @Override
    public Result expandCurrent(TenantContext tenant, UUID roleId) {
        Role role = roles.findRole(tenant, roleId).orElse(null);
        if (role == null) {
            return Result.unavailable(Status.NOT_FOUND, roleId);
        }
        if (role.lifecycleState() != CatalogLifecycleState.ACTIVE) {
            return Result.unavailable(Status.RETIRED, roleId);
        }
        if (role.type() == Role.RoleType.APPLICATION
                && catalog.findApplication(tenant, role.applicationId())
                        .filter(application -> application.lifecycleState()
                                == CatalogLifecycleState.ACTIVE)
                        .isEmpty()) {
            return Result.unavailable(Status.INVALID, roleId);
        }
        RoleVersion version = roles.findActiveVersion(
                        tenant, roleId)
                .orElse(null);
        if (version == null) {
            return Result.unavailable(
                    Status.NO_ACTIVE_VERSION, roleId);
        }

        List<EntitlementPath> paths = new ArrayList<>();
        var members = roles.findMembers(tenant, version.id());
        if (members.isEmpty() || members.size() > RoleCardinality.MAX_DIRECT_MEMBERS) {
            return Result.unavailable(Status.INVALID, roleId);
        }
        for (RoleVersionMember member : members) {
            if (member.kind()
                    == RoleVersionMember.MemberKind.ENTITLEMENT) {
                var path = entitlementPath(
                        tenant,
                        member.memberEntitlementId(),
                        List.of(version.id()));
                if (path == null) {
                    return Result.unavailable(Status.INVALID, roleId);
                }
                if (paths.size() == RoleCardinality.MAX_EXPANDED_PATHS) {
                    return Result.unavailable(Status.INVALID, roleId);
                }
                paths.add(path);
                continue;
            }

            if (role.type() != Role.RoleType.BUSINESS) {
                return Result.unavailable(Status.INVALID, roleId);
            }
            Role child = roles.findRole(
                            tenant, member.memberRoleId())
                    .orElse(null);
            if (child == null
                    || child.type() != Role.RoleType.APPLICATION
                    || child.lifecycleState()
                            != CatalogLifecycleState.ACTIVE
                    || catalog.findApplication(tenant, child.applicationId())
                            .filter(application -> application.lifecycleState()
                                    == CatalogLifecycleState.ACTIVE)
                            .isEmpty()) {
                return Result.unavailable(Status.INVALID, roleId);
            }
            RoleVersion childVersion = roles.findActiveVersion(
                            tenant, child.id())
                    .orElse(null);
            if (childVersion == null) {
                return Result.unavailable(Status.INVALID, roleId);
            }
            var childMembers = roles.findMembers(tenant, childVersion.id());
            if (childMembers.isEmpty() || childMembers.size() > RoleCardinality.MAX_DIRECT_MEMBERS
                    || childMembers.size() > RoleCardinality.MAX_EXPANDED_PATHS - paths.size()) {
                return Result.unavailable(Status.INVALID, roleId);
            }
            for (RoleVersionMember childMember : childMembers) {
                if (childMember.kind()
                        != RoleVersionMember.MemberKind.ENTITLEMENT) {
                    return Result.unavailable(Status.INVALID, roleId);
                }
                var path = entitlementPath(
                        tenant,
                        childMember.memberEntitlementId(),
                        List.of(version.id(), childVersion.id()));
                if (path == null) {
                    return Result.unavailable(Status.INVALID, roleId);
                }
                paths.add(path);
            }
        }
        return Result.available(roleId, paths);
    }

    private EntitlementPath entitlementPath(
            TenantContext tenant,
            UUID entitlementId,
            List<UUID> roleVersionPath) {
        var entitlement = catalog.findEntitlement(
                        tenant, entitlementId)
                .orElse(null);
        if (entitlement == null
                || entitlement.lifecycleState()
                        != CatalogLifecycleState.ACTIVE
                || entitlement.applicationTargetId() == null
                || catalog.findApplication(tenant, entitlement.applicationId())
                        .filter(application -> application.lifecycleState()
                                == CatalogLifecycleState.ACTIVE)
                        .isEmpty()) {
            return null;
        }
        var target = catalog.findTarget(
                        tenant, entitlement.applicationTargetId())
                .orElse(null);
        if (target == null
                || target.lifecycleState()
                        != CatalogLifecycleState.ACTIVE) {
            return null;
        }
        return new EntitlementPath(
                entitlement.id(),
                entitlement.applicationTargetId(),
                roleVersionPath);
    }
}
