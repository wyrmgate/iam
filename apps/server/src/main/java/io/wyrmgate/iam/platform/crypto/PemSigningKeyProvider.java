package io.wyrmgate.iam.platform.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory PEM-backed signing key adapter intended for temporary DEV/DEMO managed-runtime use.
 *
 * <p>Private keys must be PKCS#8 PEM and public keys must be X.509 PEM. Raw PEM input is parsed
 * during construction and is not exposed through the provider contract.</p>
 */
public final class PemSigningKeyProvider implements SigningKeyProvider {

    private final PrivateKey privateKey;
    private final SigningKeyMaterial keyMaterial;
    private final Map<String, SigningKeyMaterial> verificationKeys;

    public PemSigningKeyProvider(
            String keyId,
            String keyAlgorithm,
            String signingAlgorithm,
            String privateKeyPem,
            String publicKeyPem) {
        if (keyAlgorithm == null || keyAlgorithm.isBlank()) {
            throw new IllegalArgumentException("keyAlgorithm must not be blank");
        }
        Objects.requireNonNull(privateKeyPem, "privateKeyPem");
        Objects.requireNonNull(publicKeyPem, "publicKeyPem");

        try {
            KeyFactory keyFactory = KeyFactory.getInstance(keyAlgorithm);
            this.privateKey = keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(readPem(privateKeyPem, "PRIVATE KEY")));
            PublicKey publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(readPem(publicKeyPem, "PUBLIC KEY")));
            this.keyMaterial = new SigningKeyMaterial(keyId, signingAlgorithm, publicKey);
            this.verificationKeys = Map.of(keyId, keyMaterial);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to load inline signing key material", exception);
        }
    }

    @Override
    public SigningKeyMaterial currentSigningKey() {
        return keyMaterial;
    }

    @Override
    public Optional<SigningKeyMaterial> verificationKey(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(verificationKeys.get(keyId));
    }

    @Override
    public List<SigningKeyMaterial> verificationKeys() {
        return List.of(keyMaterial);
    }

    @Override
    public byte[] sign(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            Signature signature = Signature.getInstance(keyMaterial.signingAlgorithm());
            signature.initSign(privateKey);
            signature.update(payload);
            return signature.sign();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign payload with key " + keyMaterial.keyId(), exception);
        }
    }

    private static byte[] readPem(String content, String label) {
        String value = content.trim();
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!value.startsWith(begin) || !value.endsWith(end)) {
            throw new IllegalArgumentException("Expected " + label + " PEM content");
        }
        String encoded = value
                .substring(begin.length(), value.length() - end.length())
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(encoded);
    }
}
