package io.wyrmgate.iam.platform.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSigningKeyProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsPkcs8PrivateAndX509PublicKeyMaterial() throws Exception {
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
                privateKey,
                publicKey);

        SigningKeyMaterial material = provider.currentSigningKey();
        assertThat(material.keyId()).isEqualTo("local-test-key");
        assertThat(material.algorithm()).isEqualTo("RSA");
        assertThat(material.privateKey().getEncoded()).isEqualTo(keyPair.getPrivate().getEncoded());
        assertThat(material.publicKey().getEncoded()).isEqualTo(keyPair.getPublic().getEncoded());
    }

    private static String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + label + "-----\n";
    }
}
