package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.CatalogLifecycleState;
import io.wyrmgate.iam.catalog.domain.Role;
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
        RoleVersion version = roles.findActiveVersion(
                        tenant, roleId)
                .orElse(null);
        if (version == null) {
            return Result.unavailable(
                    Status.NO_ACTIVE_VERSION, roleId);
        }

        List<EntitlementPath> paths = new ArrayList<>();
        for (RoleVersionMember member : roles.findMembers(
                tenant, version.id())) {
            if (member.kind()
                    == RoleVersionMember.MemberKind.ENTITLEMENT) {
                var path = entitlementPath(
                        tenant,
                        member.memberEntitlementId(),
                        List.of(version.id()));
                if (path == null) {
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
                            != CatalogLifecycleState.ACTIVE) {
                return Result.unavailable(Status.INVALID, roleId);
            }
            RoleVersion childVersion = roles.findActiveVersion(
                            tenant, child.id())
                    .orElse(null);
            if (childVersion == null) {
                return Result.unavailable(Status.INVALID, roleId);
            }
            for (RoleVersionMember childMember : roles.findMembers(
                    tenant, childVersion.id())) {
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
                || entitlement.applicationTargetId() == null) {
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
