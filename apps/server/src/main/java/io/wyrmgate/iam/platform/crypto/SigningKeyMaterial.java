package io.wyrmgate.iam.platform.crypto;

import java.security.PublicKey;
import java.util.Objects;

/**
 * Public metadata for the currently active signing key.
 *
 * <p>Private key material is intentionally not exposed by this contract so the
 * same port can be implemented by non-exportable Vault, KMS, and HSM keys.</p>
 */
public record SigningKeyMaterial(
        String keyId,
        String signingAlgorithm,
        PublicKey publicKey) {

    public SigningKeyMaterial {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        if (signingAlgorithm == null || signingAlgorithm.isBlank()) {
            throw new IllegalArgumentException("signingAlgorithm must not be blank");
        }
        Objects.requireNonNull(publicKey, "publicKey");
    }
}
