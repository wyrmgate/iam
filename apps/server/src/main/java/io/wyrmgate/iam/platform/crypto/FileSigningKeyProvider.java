package io.wyrmgate.iam.platform.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * File-backed signing key adapter intended for local, DEV, and DEMO topologies.
 *
 * <p>Private keys must be PKCS#8 PEM and public keys must be X.509 PEM. Files
 * are read when the provider is constructed. Private material remains inside
 * this adapter and is never exposed through the provider contract.</p>
 */
public final class FileSigningKeyProvider implements SigningKeyProvider {

    private final PrivateKey privateKey;
    private final SigningKeyMaterial keyMaterial;
    private final Map<String, SigningKeyMaterial> verificationKeys;

    public FileSigningKeyProvider(
            String keyId,
            String keyAlgorithm,
            String signingAlgorithm,
            Path privateKeyPath,
            Path publicKeyPath) {
        this(keyId, keyAlgorithm, signingAlgorithm, privateKeyPath, publicKeyPath, Map.of());
    }

    public FileSigningKeyProvider(
            String keyId,
            String keyAlgorithm,
            String signingAlgorithm,
            Path privateKeyPath,
            Path publicKeyPath,
            Map<String, Path> additionalVerificationPublicKeyPaths) {
        Objects.requireNonNull(privateKeyPath, "privateKeyPath");
        Objects.requireNonNull(publicKeyPath, "publicKeyPath");
        Objects.requireNonNull(additionalVerificationPublicKeyPaths, "additionalVerificationPublicKeyPaths");

        LoadedKeys loaded = load(keyAlgorithm, privateKeyPath, publicKeyPath);
        this.privateKey = loaded.privateKey();
        this.keyMaterial = new SigningKeyMaterial(keyId, signingAlgorithm, loaded.publicKey());

        Map<String, SigningKeyMaterial> keys = new LinkedHashMap<>();
        keys.put(keyId, keyMaterial);
        additionalVerificationPublicKeyPaths.forEach((verificationKeyId, path) -> {
            if (verificationKeyId == null || verificationKeyId.isBlank()) {
                throw new IllegalArgumentException("verification key ID must not be blank");
            }
            if (keys.containsKey(verificationKeyId)) {
                throw new IllegalArgumentException("duplicate verification key ID " + verificationKeyId);
            }
            keys.put(
                    verificationKeyId,
                    new SigningKeyMaterial(
                            verificationKeyId,
                            signingAlgorithm,
                            loadPublicKey(keyAlgorithm, path)));
        });
        this.verificationKeys = Map.copyOf(keys);
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
    public byte[] sign(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            Signature signature = Signature.getInstance(keyMaterial.signingAlgorithm());
            signature.initSign(privateKey);
            signature.update(payload);
            return signature.sign();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign payload with key " + keyMaterial.keyId(), ex);
        }
    }

    private static LoadedKeys load(
            String keyAlgorithm,
            Path privateKeyPath,
            Path publicKeyPath) {
        if (keyAlgorithm == null || keyAlgorithm.isBlank()) {
            throw new IllegalArgumentException("keyAlgorithm must not be blank");
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(keyAlgorithm);
            PrivateKey privateKey = keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(readPem(privateKeyPath, "PRIVATE KEY")));
            PublicKey publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(readPem(publicKeyPath, "PUBLIC KEY")));
            return new LoadedKeys(privateKey, publicKey);
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to load signing key material", ex);
        }
    }

    private static PublicKey loadPublicKey(String keyAlgorithm, Path publicKeyPath) {
        Objects.requireNonNull(publicKeyPath, "publicKeyPath");
        if (keyAlgorithm == null || keyAlgorithm.isBlank()) {
            throw new IllegalArgumentException("keyAlgorithm must not be blank");
        }
        try {
            return KeyFactory.getInstance(keyAlgorithm)
                    .generatePublic(new X509EncodedKeySpec(readPem(publicKeyPath, "PUBLIC KEY")));
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to load verification key material", ex);
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

    private record LoadedKeys(PrivateKey privateKey, PublicKey publicKey) {
    }
}
