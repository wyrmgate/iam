package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Composed semantic query for the optional governed Application access gate. */
@FunctionalInterface
public interface IdpApplicationAccessQuery {

    boolean hasCurrentAccess(
            TenantContext tenant,
            UUID identityId,
            UUID applicationId,
            Instant at);
}
