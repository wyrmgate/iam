package io.wyrmgate.iam.platform.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PlatformSigningProperties.class)
class PlatformSigningConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "iam.signing", name = "enabled", havingValue = "true")
    SigningKeyProvider signingKeyProvider(PlatformSigningProperties properties) {
        return new FileSigningKeyProvider(
                properties.requiredKeyId(),
                properties.requiredKeyAlgorithm(),
                properties.requiredSigningAlgorithm(),
                properties.requiredPrivateKeyPath(),
                properties.requiredPublicKeyPath(),
                properties.verificationPublicKeyPaths());
    }
}
