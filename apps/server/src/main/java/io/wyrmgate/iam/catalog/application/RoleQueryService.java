package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.application.RoleQueryModels.RolePage;
import io.wyrmgate.iam.catalog.application.RoleQueryModels.RoleVersionDetail;
import io.wyrmgate.iam.catalog.application.RoleQueryModels.RoleVersionPage;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.catalog.domain.RoleVersion;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RoleQueryService {

    private final RoleRepository roles;

    public RoleQueryService(RoleRepository roles) {
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    public Optional<Role> findRole(
            TenantContext tenant, UUID roleId) {
        return roles.findRole(tenant, roleId);
    }

    public RolePage listRoles(
            TenantContext tenant, PagePosition after, int limit) {
        requireLimit(limit);
        List<Role> fetched = roles.findRolePage(
                tenant, after, limit + 1);
        boolean more = fetched.size() > limit;
        List<Role> items = List.copyOf(
                fetched.subList(0, Math.min(limit, fetched.size())));
        PagePosition next = more
                ? new PagePosition(
                        items.getLast().createdAt(),
                        items.getLast().id())
                : null;
        return new RolePage(items, next);
    }

    public Optional<RoleVersionDetail> findVersion(
            TenantContext tenant,
            UUID roleId,
            UUID roleVersionId) {
        Optional<RoleVersion> version =
                roles.findVersion(tenant, roleVersionId);
        if (version.isEmpty()
                || !version.get().roleId().equals(roleId)) {
            return Optional.empty();
        }
        return Optional.of(new RoleVersionDetail(
                version.get(),
                roles.findMembers(tenant, roleVersionId)));
    }

    public RoleVersionPage listVersions(
            TenantContext tenant,
            UUID roleId,
            PagePosition after,
            int limit) {
        requireLimit(limit);
        List<RoleVersion> fetched = roles.findVersionPage(
                tenant, roleId, after, limit + 1);
        boolean more = fetched.size() > limit;
        List<RoleVersion> items = List.copyOf(
                fetched.subList(0, Math.min(limit, fetched.size())));
        PagePosition next = more
                ? new PagePosition(
                        items.getLast().createdAt(),
                        items.getLast().id())
                : null;
        return new RoleVersionPage(items, next);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 200");
        }
    }
}
