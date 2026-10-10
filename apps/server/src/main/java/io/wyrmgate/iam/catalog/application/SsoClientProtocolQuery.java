package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.SsoClientRegistration;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;

/**
 * Catalog-owned semantic query used by the public IdP protocol adapter.
 *
 * <p>The public client identifier is server-issued and globally unique so it can safely route an
 * unauthenticated protocol request into a server-derived tenant context. Browser input never
 * supplies the authoritative Tenant.</p>
 */
public interface SsoClientProtocolQuery {

    Optional<ResolvedClient> resolveActive(String clientId);

    record ResolvedClient(TenantContext tenant, SsoClientRegistration registration) {
        public ResolvedClient {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(registration, "registration");
        }
    }
}
