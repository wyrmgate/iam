package io.wyrmgate.iam.platform.crypto;

import java.util.Optional;

/**
 * Port for asymmetric application signing without exposing private key material.
 *
 * <p>Callers depend on this interface, never on files, Vault, cloud KMS APIs, or
 * HSM-specific clients. Verification keys are resolved by key ID so bounded
 * retired public keys can remain usable across rotation.</p>
 */
public interface SigningKeyProvider {

    SigningKeyMaterial currentSigningKey();

    Optional<SigningKeyMaterial> verificationKey(String keyId);

    byte[] sign(byte[] payload);
}
