package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Semantic Catalog-owned validation used by foreign capabilities before storing stable references. */
public interface CatalogEntitlementReferenceQuery {

    Validation validateActiveTargetEntitlement(
            TenantContext tenant, UUID entitlementId, UUID applicationTargetId);

    enum Validation {
        VALID,
        NOT_FOUND,
        RETIRED,
        TARGET_MISMATCH
    }
}
