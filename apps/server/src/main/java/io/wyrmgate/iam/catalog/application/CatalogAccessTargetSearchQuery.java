package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.UUID;

/** Catalog-owned bounded lookup used by semantic access-target selectors. */
public interface CatalogAccessTargetSearchQuery {

    List<Result> search(TenantContext tenant, Kind kind, String query, int limit);

    enum Kind {
        ROLE,
        ENTITLEMENT
    }

    record Result(
            UUID id,
            Kind kind,
            String label,
            String code,
            String secondaryLabel) {}
}
