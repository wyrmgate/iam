package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Semantic Administration guard before an authentication Principal is disabled. */
@FunctionalInterface
public interface PrincipalAdministrativeContinuityGuard {

    void assertMayDisable(
            TenantContext tenant,
            UUID principalId,
            UUID identityId,
            Instant at);
}
