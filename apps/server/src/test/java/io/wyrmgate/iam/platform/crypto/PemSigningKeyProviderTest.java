package io.wyrmgate.iam.platform.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PemSigningKeyProviderTest {

    @Test
    void signsWithInlinePemWithoutExposingPrivateMaterial() throws Exception {
        KeyPair keyPair = rsaKeyPair();

        SigningKeyProvider provider = new PemSigningKeyProvider(
                "dev-inline-key",
                "RSA",
                "SHA256withRSA",
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));

        assertThat(provider.currentSigningKey().keyId()).isEqualTo("dev-inline-key");
        assertThat(provider.currentSigningKey().publicKey()).isEqualTo(keyPair.getPublic());
        assertThat(provider.verificationKeys()).hasSize(1);

        byte[] payload = "wyrmgate-inline-signing-contract".getBytes(StandardCharsets.UTF_8);
        byte[] signature = provider.sign(payload);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(payload);
        assertThat(verifier.verify(signature)).isTrue();
    }

    @Test
    void configurationRejectsMixedInlineAndFileInputs() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        PlatformSigningProperties properties = new PlatformSigningProperties(
                true,
                "dev-inline-key",
                "RSA",
                "SHA256withRSA",
                "/keys/private.pem",
                "/keys/public.pem",
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                pem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                "");

        assertThatThrownBy(() -> new PlatformSigningConfiguration().signingKeyProvider(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void configurationRequiresBothInlinePemValues() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        PlatformSigningProperties properties = new PlatformSigningProperties(
                true,
                "dev-inline-key",
                "RSA",
                "SHA256withRSA",
                "",
                "",
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                "",
                "");

        assertThatThrownBy(() -> new PlatformSigningConfiguration().signingKeyProvider(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public-key-pem");
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + label + "-----\n";
    }
}
