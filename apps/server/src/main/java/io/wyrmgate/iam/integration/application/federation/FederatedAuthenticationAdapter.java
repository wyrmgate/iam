package io.wyrmgate.iam.integration.application.federation;

import io.wyrmgate.iam.integration.application.federation.FederationTrustQuery.Protocol;
import io.wyrmgate.iam.integration.application.federation.FederationTrustQuery.VerificationContext;
import java.time.Instant;

/** Explicit typed upstream protocol adapter. Implementations MUST validate signature, issuer,
 * audience, time and protocol-specific request/nonce binding, and atomically consume durable
 * callback/replay state before returning proof. No untyped exchange or implicit activation.
 */
public interface FederatedAuthenticationAdapter<R> {
    String providerKey();
    Protocol protocol();
    Class<R> requestType();
    VerifiedFederatedSubject authenticate(VerificationContext context, R request, Instant now);
}
