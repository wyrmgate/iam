package io.wyrmgate.iam.idp.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdpProtocolFoundationTest {

    @Test
    void issuerRequiresHttpsExceptLoopbackDevelopment() {
        assertThat(new IdpProtocolProperties(true, "https://idp.example.test").requiredIssuer().toString())
                .isEqualTo("https://idp.example.test");
        assertThat(new IdpProtocolProperties(true, "http://localhost:8080/").requiredIssuer().toString())
                .isEqualTo("http://localhost:8080");

        assertThatThrownBy(() -> new IdpProtocolProperties(true, "http://idp.example.test").requiredIssuer())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new IdpProtocolProperties(true, "https://user@idp.example.test").requiredIssuer())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IdpProtocolProperties(true, "https://idp.example.test?tenant=x").requiredIssuer())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jwksPublishesOnlyPublicRsaMaterial() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        SigningKeyMaterial material = new SigningKeyMaterial("kid-1", "RS256", pair.getPublic());

        Map<String, Object> jwk = IdpJwkEncoder.encode(material);

        assertThat(jwk).containsEntry("kty", "RSA")
                .containsEntry("use", "sig")
                .containsEntry("kid", "kid-1")
                .containsEntry("alg", "RS256")
                .containsKeys("n", "e");
        assertThat(jwk).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi", "privateKey");
        assertThat(jwk.toString()).doesNotContain(pair.getPrivate().toString());
    }

    @Test
    void controllerFailsClosedWithoutPublishableSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair pair = generator.generateKeyPair();
        SigningKeyProvider provider = provider(new SigningKeyMaterial("kid-ec", "ES256", pair.getPublic()));

        assertThatThrownBy(() -> new IdpProtocolController(
                        new IdpProtocolProperties(true, "https://idp.example.test"), provider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RSA");
    }

    private static SigningKeyProvider provider(SigningKeyMaterial material) {
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
                throw new UnsupportedOperationException("signing not needed by JWKS test");
            }
        };
    }
}
