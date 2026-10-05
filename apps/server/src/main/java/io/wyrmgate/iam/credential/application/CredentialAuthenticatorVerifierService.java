package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.domain.CredentialModels.Credential;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialKind;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Credential-owned password verification service. Private material never leaves provider adapters. */
public final class CredentialAuthenticatorVerifierService implements CredentialAuthenticatorVerifier {

    private static final int MAX_CREDENTIALS_TO_EVALUATE = 32;

    private final CredentialRepository credentials;
    private final Map<String, CredentialSecretVerifierProvider> providers;

    public CredentialAuthenticatorVerifierService(
            CredentialRepository credentials,
            List<CredentialSecretVerifierProvider> providers) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        LinkedHashMap<String, CredentialSecretVerifierProvider> indexed = new LinkedHashMap<>();
        for (CredentialSecretVerifierProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            CredentialSecretVerifierProvider duplicate = indexed.put(provider.providerType(), provider);
            if (duplicate != null) {
                throw new IllegalStateException("duplicate Credential secret verifier provider: " + provider.providerType());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    @Override
    public Result verifyPassword(
            TenantContext tenant,
            UUID principalId,
            char[] presentedSecret,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(presentedSecret, "presentedSecret");
        Objects.requireNonNull(now, "now");
        if (presentedSecret.length == 0 || presentedSecret.length > 4096) return Result.invalid();

        List<Credential> candidates = credentials.listCredentials(
                tenant, principalId, null, MAX_CREDENTIALS_TO_EVALUATE + 1);
        if (candidates.size() > MAX_CREDENTIALS_TO_EVALUATE) return Result.unavailable();

        boolean providerUnavailable = false;
        try {
            for (Credential credential : candidates) {
                if (credential.kind() != CredentialKind.PASSWORD || !credential.effectiveAt(now)) continue;
                CredentialSecretVerifierProvider provider = providers.get(
                        credential.secretReference().providerType());
                if (provider == null) {
                    providerUnavailable = true;
                    continue;
                }
                CredentialSecretVerifierProvider.Verification verification = provider.verify(
                        credential.secretReference().referenceKey(), presentedSecret);
                if (verification == CredentialSecretVerifierProvider.Verification.MATCH) {
                    return Result.verified(Strength.BASELINE);
                }
                if (verification == CredentialSecretVerifierProvider.Verification.UNAVAILABLE) {
                    providerUnavailable = true;
                }
            }
            return providerUnavailable ? Result.unavailable() : Result.invalid();
        } finally {
            Arrays.fill(presentedSecret, '\0');
        }
    }
}