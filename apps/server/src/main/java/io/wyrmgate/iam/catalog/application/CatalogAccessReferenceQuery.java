package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Catalog-owned semantic reference for AccessAssignment target validation. */
public interface CatalogAccessReferenceQuery {

    EntitlementReference resolveActiveEntitlement(
            TenantContext tenant, UUID entitlementId);

    record EntitlementReference(Status status, UUID applicationTargetId) {
        public EntitlementReference {
            Objects.requireNonNull(status, "status");
            if (status == Status.VALID) {
                Objects.requireNonNull(applicationTargetId, "applicationTargetId");
            } else if (applicationTargetId != null) {
                throw new IllegalArgumentException(
                        "Only VALID entitlement references carry ApplicationTarget");
            }
        }

        public static EntitlementReference valid(UUID applicationTargetId) {
            return new EntitlementReference(Status.VALID, applicationTargetId);
        }

        public static EntitlementReference notFound() {
            return new EntitlementReference(Status.NOT_FOUND, null);
        }

        public static EntitlementReference retired() {
            return new EntitlementReference(Status.RETIRED, null);
        }

        public static EntitlementReference untargeted() {
            return new EntitlementReference(Status.UNTARGETED, null);
        }
    }

    enum Status {
        VALID,
        NOT_FOUND,
        RETIRED,
        UNTARGETED
    }
}
