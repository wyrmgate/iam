package io.wyrmgate.iam.platform.tenant;

import java.util.Objects;
import java.util.UUID;

/** Explicit tenant scope passed through application and persistence boundaries. */
public record TenantContext(UUID tenantId) {

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId");
    }
}
