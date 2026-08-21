package io.wyrmgate.iam.platform.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Objects;

/**
 * Provider-neutral signing key material exposed to cryptographic consumers.
 */
public record SigningKeyMaterial(
        String keyId,
        String algorithm,
        PrivateKey privateKey,
        PublicKey publicKey) {

    public SigningKeyMaterial {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm must not be blank");
        }
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(publicKey, "publicKey");
    }
}
