package io.wyrmgate.iam.integration.application.federation;
import java.time.Instant;
import io.wyrmgate.iam.integration.application.federation.FederationTrustQuery.VerificationContext;
@FunctionalInterface public interface OidcCallbackFence {
    boolean consume(VerificationContext context,String stateHash,String browserHash,Instant now);
}
