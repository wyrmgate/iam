package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Catalog-owned semantic expansion of the current active Role composition. */
public interface RoleExpansionQuery {

    Result expandCurrent(TenantContext tenant, UUID roleId);

    enum Status {
        AVAILABLE,
        NOT_FOUND,
        RETIRED,
        NO_ACTIVE_VERSION,
        INVALID
    }

    record Result(Status status, UUID roleId, List<EntitlementPath> paths) {
        public Result {
            Objects.requireNonNull(status, "status");
            paths = paths == null ? List.of() : List.copyOf(paths);
            if (status == Status.AVAILABLE) {
                Objects.requireNonNull(roleId, "roleId");
            } else if (!paths.isEmpty()) {
                throw new IllegalArgumentException("unavailable expansion must not carry paths");
            }
        }

        public static Result available(UUID roleId, List<EntitlementPath> paths) {
            return new Result(Status.AVAILABLE, roleId, paths);
        }

        public static Result unavailable(Status status, UUID roleId) {
            if (status == Status.AVAILABLE) throw new IllegalArgumentException("AVAILABLE requires paths");
            return new Result(status, roleId, List.of());
        }
    }

    record EntitlementPath(
            UUID entitlementId,
            UUID applicationTargetId,
            List<UUID> roleVersionPath) {
        public EntitlementPath {
            Objects.requireNonNull(entitlementId, "entitlementId");
            Objects.requireNonNull(applicationTargetId, "applicationTargetId");
            roleVersionPath = List.copyOf(roleVersionPath);
            if (roleVersionPath.isEmpty()) {
                throw new IllegalArgumentException("roleVersionPath must not be empty");
            }
        }
    }
}
