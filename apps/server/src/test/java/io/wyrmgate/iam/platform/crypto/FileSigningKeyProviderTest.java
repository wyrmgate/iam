package io.wyrmgate.iam.platform.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSigningKeyProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void signsWithoutExposingPrivateKeyMaterial() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        Path privateKey = tempDir.resolve("signing-private.pem");
        Path publicKey = tempDir.resolve("signing-public.pem");
        Files.writeString(privateKey, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        Files.writeString(publicKey, pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));

        SigningKeyProvider provider = new FileSigningKeyProvider(
                "local-test-key",
                "RSA",
                "SHA256withRSA",
                privateKey,
                publicKey);

        SigningKeyMaterial material = provider.currentSigningKey();
        assertThat(material.keyId()).isEqualTo("local-test-key");
        assertThat(material.signingAlgorithm()).isEqualTo("SHA256withRSA");
        assertThat(material.publicKey().getEncoded()).isEqualTo(keyPair.getPublic().getEncoded());

        byte[] payload = "wyrmgate-signing-contract".getBytes(StandardCharsets.UTF_8);
        byte[] signed = provider.sign(payload);

        Signature verifier = Signature.getInstance(material.signingAlgorithm());
        verifier.initVerify(material.publicKey());
        verifier.update(payload);
        assertThat(verifier.verify(signed)).isTrue();
    }

    @Test
    void retainsAdditionalPublicVerificationKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair current = generator.generateKeyPair();
        KeyPair previous = generator.generateKeyPair();

        Path privateKey = tempDir.resolve("current-private.pem");
        Path publicKey = tempDir.resolve("current-public.pem");
        Path previousPublic = tempDir.resolve("previous-public.pem");
        Files.writeString(privateKey, pem("PRIVATE KEY", current.getPrivate().getEncoded()));
        Files.writeString(publicKey, pem("PUBLIC KEY", current.getPublic().getEncoded()));
        Files.writeString(previousPublic, pem("PUBLIC KEY", previous.getPublic().getEncoded()));

        SigningKeyProvider provider = new FileSigningKeyProvider(
                "current-key",
                "RSA",
                "SHA256withRSA",
                privateKey,
                publicKey,
                Map.of("previous-key", previousPublic));

        assertThat(provider.verificationKey("current-key"))
                .get()
                .extracting(SigningKeyMaterial::keyId)
                .isEqualTo("current-key");
        assertThat(provider.verificationKey("previous-key"))
                .get()
                .extracting(SigningKeyMaterial::publicKey)
                .isEqualTo(previous.getPublic());
        assertThat(provider.verificationKey("unknown")).isEmpty();
    }

    private static String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + label + "-----\n";
    }
}
