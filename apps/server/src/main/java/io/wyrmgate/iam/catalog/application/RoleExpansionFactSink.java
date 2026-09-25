package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

public interface RoleExpansionFactSink {

    String ROLE_EXPANSION_CHANGED = "catalog.role-expansion-changed";

    void changed(
            TenantContext tenant,
            UUID roleId,
            long roleRevision,
            Instant occurredAt,
            UUID correlationId,
            UUID causationId);
}
