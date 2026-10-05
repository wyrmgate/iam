package io.wyrmgate.iam.platform.crypto;

import java.util.Collection;
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

    Collection<SigningKeyMaterial> verificationKeys();

    byte[] sign(byte[] payload);
}
