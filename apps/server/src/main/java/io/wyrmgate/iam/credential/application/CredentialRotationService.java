package io.wyrmgate.iam.credential.application;

import io.wyrmgate.iam.credential.domain.CredentialModels.*;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class CredentialRotationService {

    private final CredentialRepository repository;
    private final IdentityAccessReferenceQuery identities;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public CredentialRotationService(
            CredentialRepository repository,
            IdentityAccessReferenceQuery identities,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.identities = Objects.requireNonNull(
                identities, "identities");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    public CredentialRotation plan(
            TenantContext tenant,
            UUID oldCredentialId,
            UUID initiatorIdentityId,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(
                oldCredentialId, "oldCredentialId");
        Objects.requireNonNull(
                initiatorIdentityId, "initiatorIdentityId");
        Objects.requireNonNull(now, "now");

        if (!identities.identityExists(
                tenant, initiatorIdentityId)) {
            throw new IllegalArgumentException(
                    "rotation initiator Identity does not exist");
        }

        Credential old = repository.findCredential(
                        tenant, oldCredentialId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "old Credential does not exist"));
        if (!old.effectiveAt(now)) {
            throw new IllegalArgumentException(
                    "routine rotation requires an effective ACTIVE Credential");
        }

        CredentialRotation rotation =
                new CredentialRotation(
                        ids.nextId(),
                        oldCredentialId,
                        null,
                        initiatorIdentityId,
                        RotationState.PLANNED,
                        null,
                        null,
                        1,
                        now,
                        now,
                        null);
        return transactions.required(() -> {
            repository.insertRotation(
                    tenant, rotation);
            return repository.findRotation(
                            tenant, rotation.id())
                    .orElseThrow();
        });
    }

    public CredentialRotation beginReplacement(
            TenantContext tenant,
            UUID rotationId,
            long expectedRevision,
            Instant now) {
        return transition(
                tenant,
                rotationId,
                expectedRevision,
                RotationState.PLANNED,
                RotationState.CREATING_REPLACEMENT,
                null,
                "creating-replacement",
                null,
                now);
    }

    public CredentialRotation attachReplacement(
            TenantContext tenant,
            UUID rotationId,
            UUID replacementCredentialId,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(
                replacementCredentialId,
                "replacementCredentialId");
        return transactions.required(() -> {
            CredentialRotation current =
                    requireRotation(
                            tenant,
                            rotationId,
                            expectedRevision);
            if (current.state()
                    != RotationState.CREATING_REPLACEMENT) {
                throw new IllegalStateException(
                        "replacement may only be attached while CREATING_REPLACEMENT");
            }
            Credential old = repository.findCredential(
                            tenant,
                            current.oldCredentialId())
                    .orElseThrow();
            Credential replacement =
                    repository.findCredential(
                            tenant,
                            replacementCredentialId)
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "replacement Credential does not exist"));
            if (!old.principalId().equals(
                    replacement.principalId())) {
                throw new IllegalArgumentException(
                        "replacement Credential must belong to the same Principal");
            }
            if (replacement.id().equals(old.id())) {
                throw new IllegalArgumentException(
                        "replacement Credential must differ from old Credential");
            }
            if (replacement.state()
                    == CredentialState.REVOKED
                    || replacement.state()
                    == CredentialState.EXPIRED
                    || replacement.state()
                    == CredentialState.COMPROMISED) {
                throw new IllegalArgumentException(
                        "replacement Credential must not be terminal or compromised");
            }
            return repository.updateRotation(
                    tenant,
                    rotationId,
                    replacementCredentialId,
                    RotationState.DISTRIBUTING,
                    "replacement-attached",
                    null,
                    expectedRevision,
                    now,
                    null);
        });
    }

    public CredentialRotation markDistributed(
            TenantContext tenant,
            UUID rotationId,
            long expectedRevision,
            Instant now) {
        return transition(
                tenant,
                rotationId,
                expectedRevision,
                RotationState.DISTRIBUTING,
                RotationState.VERIFYING,
                null,
                "distributed",
                null,
                now);
    }

    public CredentialRotation markVerified(
            TenantContext tenant,
            UUID rotationId,
            long expectedRevision,
            Instant now) {
        return transactions.required(() -> {
            CredentialRotation current =
                    requireRotation(
                            tenant,
                            rotationId,
                            expectedRevision);
            if (current.state()
                    != RotationState.VERIFYING) {
                throw new IllegalStateException(
                        "rotation must be VERIFYING before cutover");
            }
            Credential replacement =
                    requireReplacement(
                            tenant, current);
            if (!replacement.effectiveAt(now)) {
                throw new IllegalStateException(
                        "replacement Credential must be effective before cutover");
            }
            return repository.updateRotation(
                    tenant,
                    rotationId,
                    current.replacementCredentialId(),
                    RotationState.CUTOVER_COMPLETE,
                    "verified",
                    null,
                    expectedRevision,
                    now,
                    null);
        });
    }

    public CredentialRotation beginOldRevocation(
            TenantContext tenant,
            UUID rotationId,
            long expectedRevision,
            Instant now) {
        return transition(
                tenant,
                rotationId,
                expectedRevision,
                RotationState.CUTOVER_COMPLETE,
                RotationState.REVOKING_OLD,
                null,
                "revoking-old",
                null,
                now);
    }

    public CredentialRotation complete(
            TenantContext tenant,
            UUID rotationId,
            long expectedRevision,
            Instant now) {
        return transactions.required(() -> {
            CredentialRotation current =
                    requireRotation(
                            tenant,
                            rotationId,
                            expectedRevision);
            if (current.state()
                    != RotationState.REVOKING_OLD) {
                throw new IllegalStateException(
                        "rotation must be REVOKING_OLD before completion");
            }
            Credential old = repository.findCredential(
                            tenant,
                            current.oldCredentialId())
                    .orElseThrow();
            Credential replacement =
                    requireReplacement(
                            tenant, current);
            if (old.effectiveAt(now)) {
                throw new IllegalStateException(
                        "old Credential must no longer be effective before rotation completes");
            }
            if (!replacement.effectiveAt(now)) {
                throw new IllegalStateException(
                        "replacement Credential must remain effective at completion");
            }
            return repository.updateRotation(
                    tenant,
                    rotationId,
                    current.replacementCredentialId(),
                    RotationState.COMPLETED,
                    "completed",
                    null,
                    expectedRevision,
                    now,
                    now);
        });
    }

    public CredentialRotation fail(
            TenantContext tenant,
            UUID rotationId,
            long expectedRevision,
            RotationState failureState,
            String failureCode,
            Instant now) {
        if (failureState != RotationState.FAILED
                && failureState
                    != RotationState.MANUAL_REQUIRED
                && failureState
                    != RotationState.FAILED_REMEDIATION) {
            throw new IllegalArgumentException(
                    "failureState must be a terminal failure state");
        }
        String normalized = normalizeFailureCode(
                failureCode);
        return transactions.required(() -> {
            CredentialRotation current =
                    requireRotation(
                            tenant,
                            rotationId,
                            expectedRevision);
            if (CredentialRotation.isTerminal(
                    current.state())) {
                throw new IllegalStateException(
                        "terminal CredentialRotation is immutable");
            }
            return repository.updateRotation(
                    tenant,
                    rotationId,
                    current.replacementCredentialId(),
                    failureState,
                    current.checkpoint(),
                    normalized,
                    expectedRevision,
                    now,
                    now);
        });
    }

    private CredentialRotation transition(
            TenantContext tenant,
            UUID rotationId,
            long expectedRevision,
            RotationState required,
            RotationState next,
            UUID replacementCredentialId,
            String checkpoint,
            String failureCode,
            Instant now) {
        return transactions.required(() -> {
            CredentialRotation current =
                    requireRotation(
                            tenant,
                            rotationId,
                            expectedRevision);
            if (current.state() != required) {
                throw new IllegalStateException(
                        "CredentialRotation is not in required state "
                                + required);
            }
            UUID replacement =
                    replacementCredentialId != null
                            ? replacementCredentialId
                            : current.replacementCredentialId();
            if (next.ordinal()
                    >= RotationState.DISTRIBUTING.ordinal()
                    && next.ordinal()
                    <= RotationState.COMPLETED.ordinal()
                    && replacement == null) {
                throw new IllegalStateException(
                        "rotation requires replacement Credential before "
                                + next);
            }
            return repository.updateRotation(
                    tenant,
                    rotationId,
                    replacement,
                    next,
                    checkpoint,
                    failureCode,
                    expectedRevision,
                    now,
                    CredentialRotation.isTerminal(next)
                            ? now
                            : null);
        });
    }

    private CredentialRotation requireRotation(
            TenantContext tenant,
            UUID rotationId,
            long expectedRevision) {
        if (expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "expectedRevision must be positive");
        }
        CredentialRotation current =
                repository.findRotation(
                        tenant, rotationId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "CredentialRotation does not exist"));
        if (current.revision()
                != expectedRevision) {
            throw new StaleWriteException(
                    "credential-rotation",
                    rotationId,
                    expectedRevision);
        }
        return current;
    }

    private Credential requireReplacement(
            TenantContext tenant,
            CredentialRotation rotation) {
        if (rotation.replacementCredentialId()
                == null) {
            throw new IllegalStateException(
                    "rotation does not have a replacement Credential");
        }
        return repository.findCredential(
                        tenant,
                        rotation.replacementCredentialId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "replacement Credential does not exist"));
    }

    private static String normalizeFailureCode(
            String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "failureCode is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "failureCode exceeds 128 characters");
        }
        return normalized;
    }
}
