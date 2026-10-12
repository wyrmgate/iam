package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.domain.CredentialModels.SecretReference;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Private Credential-owned boundary for creating local password verifier material. */
public interface PasswordSecretEnrollment {

    String PROVIDER_TYPE = "wyrmgate-local-password-v1";

    /**
     * Idempotently establishes verifier material for the exact bootstrap Credential.
     * Implementations must never persist or log the presented plaintext password.
     */
    SecretReference ensure(
            TenantContext tenant,
            UUID credentialId,
            char[] password,
            Instant now);
}
