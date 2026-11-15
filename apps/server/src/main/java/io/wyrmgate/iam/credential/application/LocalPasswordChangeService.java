package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.domain.CredentialModels.*;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Atomic, local-only self-service password rotation. The private verifier adapter must
 * participate in the same transaction as Credential persistence. Never substitute an
 * external provider for this local execution path.
 */
public final class LocalPasswordChangeService {
    public static final int MIN_NEW_PASSWORD_LENGTH = 15;
    public static final int MAX_PASSWORD_LENGTH = 1024;

    private final CredentialRepository repository;
    private final CredentialService credentials;
    private final CredentialRotationService rotations;
    private final CredentialBoundaryScheduler boundaries;
    private final IdentityAuthenticationQuery identities;
    private final PasswordSecretEnrollment enrollment;
    private final CredentialSecretVerifier verifier;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public LocalPasswordChangeService(CredentialRepository repository, CredentialService credentials,
            CredentialRotationService rotations, CredentialBoundaryScheduler boundaries,
            IdentityAuthenticationQuery identities, PasswordSecretEnrollment enrollment,
            CredentialSecretVerifier verifier, IdGenerator ids, TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository);
        this.credentials = Objects.requireNonNull(credentials);
        this.rotations = Objects.requireNonNull(rotations);
        this.boundaries = Objects.requireNonNull(boundaries);
        this.identities = Objects.requireNonNull(identities);
        this.enrollment = Objects.requireNonNull(enrollment);
        this.verifier = Objects.requireNonNull(verifier);
        this.ids = Objects.requireNonNull(ids);
        this.transactions = Objects.requireNonNull(transactions);
    }

    /** The caller supplies server-derived session context and retains ownership of input arrays. */
    public Optional<CredentialRotation> change(TenantContext tenant, UUID identityId, UUID principalId,
            UUID oldCredentialId, long expectedRevision, char[] currentPassword, char[] newPassword,
            Instant now) {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(identityId);
        Objects.requireNonNull(principalId);
        Objects.requireNonNull(oldCredentialId);
        Objects.requireNonNull(now);
        Objects.requireNonNull(currentPassword);
        Objects.requireNonNull(newPassword);
        if (expectedRevision < 1 || currentPassword.length == 0 || currentPassword.length > MAX_PASSWORD_LENGTH
                || newPassword.length < MIN_NEW_PASSWORD_LENGTH || newPassword.length > MAX_PASSWORD_LENGTH
                || Arrays.equals(currentPassword, newPassword)) return Optional.empty();
        char[] current = currentPassword.clone();
        char[] replacement = newPassword.clone();
        try {
            return transactions.required(() -> {
                Credential old = repository.lockCredential(tenant, oldCredentialId).orElse(null);
                if (old == null || old.revision() != expectedRevision
                        || !old.principalId().equals(principalId) || old.kind() != CredentialKind.PASSWORD
                        || !old.effectiveAt(now)
                        || !PasswordSecretEnrollment.PROVIDER_TYPE.equals(old.secretReference().providerType())
                        || repository.findOpenRotation(tenant, old.id()).isPresent()
                        || identities.eligibleSubject(tenant, principalId)
                                .filter(subject -> subject.identityId().equals(identityId)).isEmpty()) {
                    return Optional.empty();
                }
                // Ambiguous local authenticators require explicit recovery; never replace a subset.
                List<Credential> candidates = repository.findEffectiveCredentials(tenant, principalId,
                        CredentialKind.PASSWORD, now, CredentialAuthenticationService.MAX_PASSWORD_CANDIDATES + 1);
                if (candidates.size() > CredentialAuthenticationService.MAX_PASSWORD_CANDIDATES
                        || candidates.stream().filter(c -> PasswordSecretEnrollment.PROVIDER_TYPE
                                .equals(c.secretReference().providerType())).count() != 1
                        || !verifier.matches(tenant, old.id(), old.secretReference(), current)) {
                    return Optional.empty();
                }
                CredentialRotation rotation = rotations.plan(tenant, old.id(), identityId, now);
                rotation = rotations.beginReplacement(tenant, rotation.id(), rotation.revision(), now);
                UUID replacementId = ids.nextId();
                SecretReference ref = enrollment.ensure(tenant, replacementId, replacement, now);
                if (!ref.equals(new SecretReference(PasswordSecretEnrollment.PROVIDER_TYPE,
                        replacementId.toString())) || !verifier.matches(tenant, replacementId, ref, replacement)) {
                    throw new IllegalStateException("local replacement verification failed");
                }
                Credential next = new Credential(replacementId, principalId, CredentialKind.PASSWORD,
                        ref, CredentialState.ACTIVE, now, old.validUntil(), 1, now, now, null, null, null);
                repository.insertCredential(tenant, next);
                boundaries.scheduleBoundaries(tenant, next, now);
                rotation = rotations.attachReplacement(tenant, rotation.id(), next.id(), rotation.revision(), now);
                // Distribution and verification are local installation of the verified private verifier.
                // No external consumer or provider execution is asserted.
                rotation = rotations.markDistributed(tenant, rotation.id(), rotation.revision(), now);
                rotation = rotations.markVerified(tenant, rotation.id(), rotation.revision(), now);
                rotation = rotations.beginOldRevocation(tenant, rotation.id(), rotation.revision(), now);
                credentials.revoke(tenant, old.id(), old.revision(), now);
                return Optional.of(rotations.complete(tenant, rotation.id(), rotation.revision(), now));
            });
        } finally {
            Arrays.fill(current, (char) 0);
            Arrays.fill(replacement, (char) 0);
        }
    }
}
