package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.api.security.ControlPlaneAuthProperties;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IdentityCursorProperties.class)
class IdentityApiConfiguration {

    @Bean
    IdentityApiMutationService identityApiMutationService(
            AdministrativeAuthorizationService authorization,
            IdentityCommandService commands,
            IdentityRepository identities,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        return new IdentityApiMutationService(
                authorization, commands, identities, idempotency, transactions);
    }

    @Bean
    IdentityCursorCodec identityCursorCodec(
            ObjectProvider<SigningKeyProvider> signingKeys,
            IdentityCursorProperties properties,
            ControlPlaneAuthProperties authProperties) {
        SigningKeyProvider provider = signingKeys.getIfAvailable();
        if (authProperties.enabled() && provider == null) {
            throw new IllegalStateException(
                    "iam.signing.enabled=true and signing key material are required when control-plane authentication is enabled");
        }
        return new IdentityCursorCodec(provider, properties.effectiveLifetime(), Clock.systemUTC());
    }
}
