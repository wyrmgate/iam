package io.wyrmgate.iam.platform.crypto;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Port for asymmetric application signing without exposing private key material.
 *
 * <p>Callers depend on this interface, never on files, Vault, cloud KMS APIs, or
 * HSM-specific clients. Verification keys are public material and may be
 * enumerated so standards-based consumers can validate tokens across a bounded
 * signing-key rotation overlap. Private signing material remains encapsulated
 * by the adapter.</p>
 */
public interface SigningKeyProvider {

    SigningKeyMaterial currentSigningKey();

    Optional<SigningKeyMaterial> verificationKey(String keyId);

    /**
     * Public verification material accepted by this provider. Providers that do
     * not support historical verification keys remain source-compatible and
     * expose their current public key by default.
     */
    default Collection<SigningKeyMaterial> verificationKeys() {
        return List.of(currentSigningKey());
    }

    byte[] sign(byte[] payload);
}
