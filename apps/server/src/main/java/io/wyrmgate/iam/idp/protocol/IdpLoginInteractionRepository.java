package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Platform-only short-lived interaction state; never a business authority. */
interface IdpLoginInteractionRepository {
    record Interaction(String formHash, String browserHash, TenantContext tenant, UUID registrationId,
            long clientRevision, UUID targetId, IdpProtocolService.AuthorizationRequest request, Instant expiresAt) {
        @Override public String toString() { return "Interaction[REDACTED]"; }
    }
    void insert(Interaction interaction);
    Optional<Interaction> consume(String formHash, String browserHash, Instant now);
}
