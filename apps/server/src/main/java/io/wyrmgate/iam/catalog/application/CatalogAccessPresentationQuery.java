package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Catalog-owned bounded presentation query for Access operator views.
 *
 * <p>This read-only semantic collaboration surface returns human labels for already authorized
 * stable Catalog references. It does not transfer Catalog authority to Access.</p>
 */
public interface CatalogAccessPresentationQuery {

    Labels labels(TenantContext tenant, Set<UUID> roleIds, Set<UUID> entitlementIds);

    record Labels(Map<UUID, String> roles, Map<UUID, String> entitlements) {
        public Labels {
            roles = Map.copyOf(roles);
            entitlements = Map.copyOf(entitlements);
        }
    }
}
