package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Semantic query for deployment-scoped PLATFORM_OWNER authority. */
@FunctionalInterface
public interface PlatformOwnerAuthorityQuery {

    boolean isViablePlatformOwner(
            TenantContext actorTenant,
            UUID actorIdentityId,
            Instant at);
}
