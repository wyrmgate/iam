package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.domain.CredentialModels.Credential;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialKind;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Credential-owned semantic service for first-party local password verification. */
public final class CredentialAuthenticationService {

    private final CredentialRepository credentials;
    private final List<CredentialSecretVerifier> verifiers;

    public CredentialAuthenticationService(
            CredentialRepository credentials,
            List<CredentialSecretVerifier> verifiers) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.verifiers = List.copyOf(Objects.requireNonNull(verifiers, "verifiers"));
    }

    /**
     * Verifies a presented password against effective PASSWORD credentials for one Principal.
     *
     * <p>The caller retains ownership of {@code presentedSecret}. This method works on a private
     * copy and clears that copy before returning. Unsupported, missing, ambiguous, revoked,
     * compromised, scheduled and expired authenticators all fail closed without exposing a
     * distinguishable reason.
     */
    public Optional<VerifiedCredential> verifyPassword(
            TenantContext tenant,
            UUID principalId,
            char[] presentedSecret,
            Instant at) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(presentedSecret, "presentedSecret");
        Objects.requireNonNull(at, "at");
        if (presentedSecret.length == 0) return Optional.empty();

        char[] privateCopy = Arrays.copyOf(presentedSecret, presentedSecret.length);
        try {
            for (Credential credential : credentials.findEffectiveCredentials(
                    tenant,
                    principalId,
                    CredentialKind.PASSWORD,
                    at)) {
                CredentialSecretVerifier verifier = uniqueVerifier(
                        credential.secretReference().providerType());
                if (verifier != null
                        && verifier.matches(credential.secretReference(), privateCopy)) {
                    return Optional.of(new VerifiedCredential(
                            principalId, credential.id(), credential.revision()));
                }
            }
            return Optional.empty();
        } finally {
            Arrays.fill(privateCopy, '\0');
        }
    }

    /**
     * Revalidates the authenticator that established an existing browser session. Any lifecycle,
     * ownership, type, revision or temporal change invalidates the session fail closed.
     */
    public boolean isStillValid(
            TenantContext tenant,
            UUID principalId,
            UUID credentialId,
            long credentialRevision,
            Instant at) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(at, "at");
        if (credentialRevision < 1) return false;
        return credentials.findCredential(tenant, credentialId)
                .filter(credential -> credential.principalId().equals(principalId))
                .filter(credential -> credential.kind() == CredentialKind.PASSWORD)
                .filter(credential -> credential.revision() == credentialRevision)
                .filter(credential -> credential.effectiveAt(at))
                .isPresent();
    }

    private CredentialSecretVerifier uniqueVerifier(String providerType) {
        CredentialSecretVerifier selected = null;
        for (CredentialSecretVerifier verifier : verifiers) {
            if (!verifier.supports(providerType)) continue;
            if (selected != null) return null;
            selected = verifier;
        }
        return selected;
    }

    /** Proof that Credential verified one authenticator for exactly one Principal. */
    public record VerifiedCredential(
            UUID principalId,
            UUID credentialId,
            long credentialRevision) {
        public VerifiedCredential {
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(credentialId, "credentialId");
            if (credentialRevision < 1) {
                throw new IllegalArgumentException("credentialRevision must be positive");
            }
        }
    }
}
