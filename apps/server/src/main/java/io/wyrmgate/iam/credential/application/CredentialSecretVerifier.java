package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.domain.CredentialModels.SecretReference;

/**
 * Private adapter boundary for checking presented authenticator material.
 *
 * <p>Implementations resolve the opaque {@link SecretReference} through an approved secret store
 * or verifier service. Raw secret material must never be returned to Credential or written to
 * logs, audit, events, task payloads or persistence.
 */
public interface CredentialSecretVerifier {

    /** Returns whether this adapter owns the supplied secret-provider type. */
    boolean supports(String providerType);

    /**
     * Performs a constant-time-equivalent verifier operation where supported by the backing
     * provider. Implementations must not retain {@code presentedSecret} after this call.
     */
    boolean matches(SecretReference reference, char[] presentedSecret);
}
