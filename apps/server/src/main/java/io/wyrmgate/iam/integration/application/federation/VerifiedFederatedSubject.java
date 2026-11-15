package io.wyrmgate.iam.integration.application.federation;

import io.wyrmgate.iam.integration.application.federation.FederationTrustQuery.VerificationContext;
import java.time.Instant;
import java.util.Objects;

/** Trusted adapter output after cryptographic validation AND durable one-use callback consumption.
 *  A value of this type alone is not a cryptographic proof or a public input contract.
 */
public record VerifiedFederatedSubject(VerificationContext context, String exactSubject,
                                      Instant authenticatedAt, Instant expiresAt) {
    public VerifiedFederatedSubject {
        Objects.requireNonNull(context); Objects.requireNonNull(authenticatedAt); Objects.requireNonNull(expiresAt);
        if (exactSubject == null || exactSubject.isBlank() || exactSubject.length() > 512
                || !exactSubject.equals(exactSubject.strip()) || !expiresAt.isAfter(authenticatedAt)) {
            throw new IllegalArgumentException("invalid federated proof");
        }
    }
    @Override public String toString() { return "VerifiedFederatedSubject[REDACTED]"; }
}
