package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;

/** Platform protocol signing port for the first-party authorization server. */
@FunctionalInterface
public interface IdpTokenIssuer {

    TokenPair issue(
            TenantContext tenant,
            IdpAuthorizationCode code,
            Instant now);

    record TokenPair(
            String idToken,
            String accessToken,
            long expiresInSeconds) {
        public TokenPair {
            Objects.requireNonNull(idToken, "idToken");
            Objects.requireNonNull(accessToken, "accessToken");
            if (expiresInSeconds < 1) {
                throw new IllegalArgumentException("expiresInSeconds must be positive");
            }
        }
    }
}
