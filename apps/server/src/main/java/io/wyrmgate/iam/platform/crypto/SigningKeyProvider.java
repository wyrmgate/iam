package io.wyrmgate.iam.platform.crypto;

/**
 * Port for asymmetric signing without exposing private key material.
 *
 * <p>Authorization/OIDC code depends on this interface, never on files, Vault,
 * cloud KMS APIs, or HSM-specific clients.</p>
 */
public interface SigningKeyProvider {

    SigningKeyMaterial currentSigningKey();

    byte[] sign(byte[] payload);
}
