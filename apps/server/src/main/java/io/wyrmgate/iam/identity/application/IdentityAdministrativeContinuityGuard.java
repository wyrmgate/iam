package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/**
 * Semantic cross-capability guard invoked before an ACTIVE Identity becomes administratively
 * ineligible. Administration supplies the implementation and remains owner of authority state.
 */
@FunctionalInterface
public interface IdentityAdministrativeContinuityGuard {

    void assertMayBecomeAdministrativelyIneligible(
            TenantContext tenant,
            UUID identityId,
            Instant at);
}
