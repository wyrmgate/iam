package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Semantic Catalog-owned validation used before foreign capabilities store target references. */
public interface CatalogTargetReferenceQuery {

    Validation validateActiveTarget(TenantContext tenant, UUID applicationTargetId);

    enum Validation {
        VALID,
        NOT_FOUND,
        RETIRED
    }
}
