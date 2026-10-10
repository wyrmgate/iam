package io.wyrmgate.iam.idp.federation;

import java.time.Instant;

/**
 * Explicit provider-specific federation verification boundary.
 *
 * <p>The core does not define an arbitrary map/JSON exchange object. Each provider adapter owns
 * a concrete typed request representation and must cryptographically validate it before returning
 * a minimized verified subject. Provider-specific transport, metadata, keys and network calls stay
 * behind the adapter boundary.</p>
 *
 * @param <R> provider-specific typed authentication exchange request
 */
public interface FederatedAuthenticationAdapter<R> {

    String providerKey();

    FederationProtocol protocol();

    Class<R> requestType();

    VerifiedFederatedSubject authenticate(R request, Instant now);
}
