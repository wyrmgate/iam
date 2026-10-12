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
        boolean inline = properties.hasInlineKeyMaterial();
        boolean file = properties.hasFileKeyMaterial();

        if (inline && file) {
            throw new IllegalStateException(
                    "Configure either iam.signing.*-key-pem or iam.signing.*-key-path, not both");
        }

        if (inline) {
            if (!properties.verificationPublicKeyPaths().isEmpty()) {
                throw new IllegalStateException(
                        "iam.signing.verification-keys is file-backed and cannot be combined with inline PEM signing keys");
            }
            return new PemSigningKeyProvider(
                    properties.requiredKeyId(),
                    properties.requiredKeyAlgorithm(),
                    properties.requiredSigningAlgorithm(),
                    properties.requiredPrivateKeyPem(),
                    properties.requiredPublicKeyPem());
        }

        return new FileSigningKeyProvider(
                properties.requiredKeyId(),
                properties.requiredKeyAlgorithm(),
                properties.requiredSigningAlgorithm(),
                properties.requiredPrivateKeyPath(),
                properties.requiredPublicKeyPath(),
                properties.verificationPublicKeyPaths());
    }
}
