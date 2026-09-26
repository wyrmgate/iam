package io.wyrmgate.iam.api.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class CatalogRoleApiModels {
    private CatalogRoleApiModels() {}

    record RoleResource(
            UUID id,
            String roleType,
            UUID applicationId,
            String code,
            String name,
            String lifecycleState,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record RolePage(
            List<RoleResource> items,
            String nextCursor) {}

    record RoleVersionSummary(
            UUID id,
            UUID roleId,
            long versionNumber,
            String state,
            String contentHash,
            long revision,
            Instant activatedAt,
            Instant createdAt,
            Instant updatedAt) {}

    record RoleVersionMemberResource(
            String kind,
            UUID roleId,
            UUID entitlementId,
            int ordinal) {}

    record RoleVersionResource(
            UUID id,
            UUID roleId,
            long versionNumber,
            String state,
            String contentHash,
            long revision,
            Instant activatedAt,
            Instant createdAt,
            Instant updatedAt,
            List<RoleVersionMemberResource> members) {}

    record RoleVersionPage(
            List<RoleVersionSummary> items,
            String nextCursor) {}
}
