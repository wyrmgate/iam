package io.wyrmgate.iam.api.credential;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.api.security.ControlPlaneAuthProperties;
import io.wyrmgate.iam.credential.application.CredentialRepository;
import io.wyrmgate.iam.credential.application.CredentialRotationService;
import io.wyrmgate.iam.credential.application.CredentialService;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ControlPlaneAuthProperties.class)
class CredentialApiConfiguration {

    @Bean
    CredentialApiMutationService credentialApiMutationService(
            AdministrativeAuthorizationService authorization,
            CredentialService credentials,
            CredentialRotationService rotations,
            CredentialRepository repository,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        return new CredentialApiMutationService(
                authorization,
                credentials,
                rotations,
                repository,
                idempotency,
                transactions);
    }

    @Bean
    CredentialCursorCodec credentialCursorCodec(
            ObjectProvider<SigningKeyProvider> signingKeys,
            ControlPlaneAuthProperties authProperties,
            @Value("${iam.api.cursor.lifetime:PT15M}")
                    Duration lifetime) {
        SigningKeyProvider provider =
                signingKeys.getIfAvailable();
        if (authProperties.enabled() && provider == null) {
            throw new IllegalStateException(
                    "iam.signing.enabled=true and signing key material are required when control-plane authentication is enabled");
        }
        return new CredentialCursorCodec(
                provider, lifetime, Clock.systemUTC());
    }
}
