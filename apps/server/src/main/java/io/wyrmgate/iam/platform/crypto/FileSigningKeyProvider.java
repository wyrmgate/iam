package io.wyrmgate.iam.platform.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/**
 * File-backed signing key adapter intended for local, DEV, and DEMO topologies.
 *
 * <p>Private keys must be PKCS#8 PEM and public keys must be X.509 PEM. The
 * files are read when the provider is constructed and retained only as parsed
 * Java key objects.</p>
 */
public final class FileSigningKeyProvider implements SigningKeyProvider {

    private final SigningKeyMaterial keyMaterial;

    public FileSigningKeyProvider(
            String keyId,
            String algorithm,
            Path privateKeyPath,
            Path publicKeyPath) {
        Objects.requireNonNull(privateKeyPath, "privateKeyPath");
        Objects.requireNonNull(publicKeyPath, "publicKeyPath");
        this.keyMaterial = load(keyId, algorithm, privateKeyPath, publicKeyPath);
    }

    @Override
    public SigningKeyMaterial currentSigningKey() {
        return keyMaterial;
    }

    private static SigningKeyMaterial load(
            String keyId,
            String algorithm,
            Path privateKeyPath,
            Path publicKeyPath) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
            PrivateKey privateKey = keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(readPem(privateKeyPath, "PRIVATE KEY")));
            PublicKey publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(readPem(publicKeyPath, "PUBLIC KEY")));
            return new SigningKeyMaterial(keyId, algorithm, privateKey, publicKey);
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to load signing key material", ex);
        }
    }

    private static byte[] readPem(Path path, String label) throws IOException {
        String content = Files.readString(path).trim();
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";

        if (!content.startsWith(begin) || !content.endsWith(end)) {
            throw new IllegalArgumentException("Expected " + label + " PEM at " + path);
        }

        String encoded = content
                .substring(begin.length(), content.length() - end.length())
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(encoded);
    }
}
