package io.wyrmgate.iam.credential.persistence;

import io.wyrmgate.iam.credential.application.CredentialAuthenticatorVerifier;
import io.wyrmgate.iam.credential.application.CredentialAuthenticatorVerifierService;
import io.wyrmgate.iam.credential.application.CredentialRepository;
import io.wyrmgate.iam.credential.application.CredentialSecretVerifierProvider;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Credential-owned composition for first-party Authentication verification. */
@Configuration
public class CredentialAuthenticationConfiguration {

    @Bean
    CredentialSecretVerifierProvider environmentBcryptCredentialSecretVerifierProvider() {
        return new EnvironmentBcryptCredentialSecretVerifierProvider(System.getenv());
    }

    @Bean
    CredentialAuthenticatorVerifier credentialAuthenticatorVerifier(
            CredentialRepository repository,
            List<CredentialSecretVerifierProvider> providers) {
        return new CredentialAuthenticatorVerifierService(repository, providers);
    }
}