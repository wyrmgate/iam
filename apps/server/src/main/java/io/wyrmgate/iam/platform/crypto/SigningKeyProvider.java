package io.wyrmgate.iam.platform.crypto;

/**
 * Port for obtaining the currently active asymmetric signing key material.
 *
 * <p>Authorization/OIDC code depends on this interface, never on files, Vault,
 * cloud KMS APIs, or HSM-specific clients.</p>
 */
public interface SigningKeyProvider {

    SigningKeyMaterial currentSigningKey();
}
