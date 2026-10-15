package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Catalog-owned bounded search used by administrative access-target selectors.
 * Search results are presentation/query data only and never become Access authority.
 */
public interface CatalogAccessTargetSearchQuery {

    List<TargetOption> search(
            TenantContext tenant,
            TargetKind targetKind,
            String query,
            int limit);

    enum TargetKind {
        ROLE,
        ENTITLEMENT
    }

    record TargetOption(
            UUID id,
            TargetKind targetKind,
            String label,
            String code,
            String lifecycleState) {
        public TargetOption {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(targetKind, "targetKind");
            if (label == null || label.isBlank()) throw new IllegalArgumentException("label must not be blank");
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
            if (lifecycleState == null || lifecycleState.isBlank()) throw new IllegalArgumentException("lifecycleState must not be blank");
        }
    }
}
