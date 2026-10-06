package io.wyrmgate.iam.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

class IdpSsoConfigurationTest {

    private final IdpSsoConfiguration configuration = new IdpSsoConfiguration();

    @Test
    void buildsIssuerBoundAuthorizationServerSettings() {
        var settings = configuration.idpAuthorizationServerSettings(
                new IdpSsoProperties(true, "https://iam.example.test/"));

        assertThat(settings.getIssuer()).isEqualTo("https://iam.example.test");
        assertThat(settings.getJwkSetEndpoint()).isEqualTo("/oauth2/jwks");
    }

    @Test
    void publishesCurrentAndRetiredPublicKeysWithoutPrivateParameters() throws Exception {
        KeyPair current = rsaKeyPair();
        KeyPair retired = rsaKeyPair();
        SigningKeyMaterial currentMaterial = new SigningKeyMaterial("current", "SHA256withRSA", current.getPublic());
        SigningKeyMaterial retiredMaterial = new SigningKeyMaterial("retired", "SHA256withRSA", retired.getPublic());

        SigningKeyProvider provider = new SigningKeyProvider() {
            @Override
            public SigningKeyMaterial currentSigningKey() {
                return currentMaterial;
            }

            @Override
            public Optional<SigningKeyMaterial> verificationKey(String keyId) {
                return List.of(currentMaterial, retiredMaterial).stream()
                        .filter(key -> key.keyId().equals(keyId))
                        .findFirst();
            }

            @Override
            public Collection<SigningKeyMaterial> verificationKeys() {
                return List.of(currentMaterial, retiredMaterial);
            }

            @Override
            public byte[] sign(byte[] payload) {
                throw new UnsupportedOperationException("not needed by JWKS test");
            }
        };

        JWKSource<SecurityContext> source = configuration.idpJwkSource(provider);
        List<JWK> jwks = source.get(new JWKSelector(new JWKMatcher.Builder().build()), null);

        assertThat(jwks).extracting(JWK::getKeyID).containsExactlyInAnyOrder("current", "retired");
        assertThat(jwks).allSatisfy(jwk -> {
            assertThat(jwk.isPrivate()).isFalse();
            assertThat(jwk.getKeyUse().identifier()).isEqualTo("sig");
            assertThat(jwk.getAlgorithm().getName()).isEqualTo("RS256");
            assertThat(jwk.toJSONObject()).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi", "oth");
        });
    }

    @Test
    void rejectsNonRsaVerificationKeyForCurrentBaseline() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair ec = generator.generateKeyPair();
        SigningKeyMaterial material = new SigningKeyMaterial("ec", "SHA256withECDSA", ec.getPublic());

        SigningKeyProvider provider = singleKeyProvider(material);

        assertThatThrownBy(() -> configuration.idpJwkSource(provider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currently requires RSA");
    }

    @Test
    void hasNoDefaultOrMutableClientRegistration() {
        RegisteredClientRepository clients = configuration.idpRegisteredClientRepository();

        assertThat(clients.findById("anything")).isNull();
        assertThat(clients.findByClientId("anything")).isNull();

        RegisteredClient attempted = RegisteredClient.withId("id")
                .clientId("client")
                .build();
        assertThatThrownBy(() -> clients.save(attempted))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("governed Catalog registration");
    }

    private static SigningKeyProvider singleKeyProvider(SigningKeyMaterial material) {
        return new SigningKeyProvider() {
            @Override
            public SigningKeyMaterial currentSigningKey() {
                return material;
            }

            @Override
            public Optional<SigningKeyMaterial> verificationKey(String keyId) {
                return material.keyId().equals(keyId) ? Optional.of(material) : Optional.empty();
            }

            @Override
            public byte[] sign(byte[] payload) {
                throw new UnsupportedOperationException("not needed by test");
            }
        };
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
