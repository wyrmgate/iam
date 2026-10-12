package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.domain.CredentialModels.Credential;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialKind;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialState;
import io.wyrmgate.iam.credential.domain.CredentialModels.SecretReference;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Credential-owned local password enrollment used by trusted fresh-install orchestration. */
public final class PasswordCredentialEnrollmentService {

    private final CredentialRepository credentials;
    private final IdentityAccessReferenceQuery principals;
    private final PasswordSecretEnrollment secretEnrollment;
    private final TransactionExecutor transactions;

    public PasswordCredentialEnrollmentService(
            CredentialRepository credentials,
            IdentityAccessReferenceQuery principals,
            PasswordSecretEnrollment secretEnrollment,
            TransactionExecutor transactions) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.secretEnrollment = Objects.requireNonNull(secretEnrollment, "secretEnrollment");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Credential ensureInstallationPassword(
            TenantContext tenant,
            UUID credentialId,
            UUID principalId,
            char[] password,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(now, "now");
        if (password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        if (principals.principal(tenant, principalId).status()
                == IdentityAccessReferenceQuery.Status.NOT_FOUND) {
            throw new IllegalArgumentException("Principal does not exist");
        }

        SecretReference expectedReference = new SecretReference(
                PasswordSecretEnrollment.PROVIDER_TYPE,
                credentialId.toString());
        Credential existing = credentials.findCredential(tenant, credentialId).orElse(null);
        if (existing != null && !sameInstallationCredentialMetadata(
                existing, principalId, expectedReference)) {
            throw new IllegalStateException(
                    "preallocated Credential already exists with different semantics");
        }

        SecretReference enrolledReference = secretEnrollment.ensure(
                tenant, credentialId, password, now);
        if (!enrolledReference.equals(expectedReference)) {
            throw new IllegalStateException(
                    "local password enrollment returned an unexpected secret reference");
        }
        if (existing != null) {
            if (!existing.effectiveAt(now)) {
                throw new IllegalStateException(
                        "preallocated installation Credential is not currently effective");
            }
            return existing;
        }

        return transactions.required(() -> {
            Credential raced = credentials.findCredential(tenant, credentialId).orElse(null);
            if (raced != null) {
                if (sameInstallationCredentialMetadata(raced, principalId, expectedReference)
                        && raced.effectiveAt(now)) {
                    return raced;
                }
                throw new IllegalStateException(
                        "preallocated Credential was concurrently created with different semantics");
            }
            Credential desired = new Credential(
                    credentialId,
                    principalId,
                    CredentialKind.PASSWORD,
                    expectedReference,
                    CredentialState.ACTIVE,
                    null,
                    null,
                    1,
                    now,
                    now,
                    null,
                    null,
                    null);
            credentials.insertCredential(tenant, desired);
            return credentials.findCredential(tenant, credentialId).orElseThrow();
        });
    }

    private static boolean sameInstallationCredentialMetadata(
            Credential existing,
            UUID principalId,
            SecretReference expectedReference) {
        return existing.principalId().equals(principalId)
                && existing.kind() == CredentialKind.PASSWORD
                && existing.secretReference().equals(expectedReference);
    }
}
