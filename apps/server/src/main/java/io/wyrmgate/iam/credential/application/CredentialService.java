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

public final class CredentialService {

    private final CredentialRepository repository;
    private final IdentityAccessReferenceQuery principals;
    private final CredentialBoundaryScheduler boundaries;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public CredentialService(
            CredentialRepository repository,
            IdentityAccessReferenceQuery principals,
            CredentialBoundaryScheduler boundaries,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.principals = Objects.requireNonNull(
                principals, "principals");
        this.boundaries = Objects.requireNonNull(
                boundaries, "boundaries");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    public Credential create(
            TenantContext tenant,
            UUID principalId,
            CredentialKind kind,
            SecretReference secretReference,
            Instant validFrom,
            Instant validUntil,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(
                secretReference, "secretReference");
        Objects.requireNonNull(now, "now");

        if (principals.principal(tenant, principalId).status()
                == IdentityAccessReferenceQuery.Status.NOT_FOUND) {
            throw new IllegalArgumentException(
                    "Principal does not exist");
        }
        if (validUntil != null
                && !validUntil.isAfter(now)) {
            throw new IllegalArgumentException(
                    "Credential validity must not already be ended");
        }
        if (validFrom != null
                && validUntil != null
                && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException(
                    "validUntil must be after validFrom");
        }

        CredentialState state =
                validFrom != null
                        && validFrom.isAfter(now)
                ? CredentialState.SCHEDULED
                : CredentialState.ACTIVE;
        Credential credential = new Credential(
                ids.nextId(),
                principalId,
                kind,
                secretReference,
                state,
                validFrom,
                validUntil,
                1,
                now,
                now,
                null,
                null,
                null);

        return transactions.required(() -> {
            repository.insertCredential(
                    tenant, credential);
            boundaries.scheduleBoundaries(
                    tenant, credential, now);
            return repository.findCredential(
                            tenant, credential.id())
                    .orElseThrow();
        });
    }

    public Credential compromise(
            TenantContext tenant,
            UUID credentialId,
            long expectedRevision,
            Instant now) {
        return transactions.required(() -> {
            Credential current =
                    requireCurrent(
                            tenant,
                            credentialId,
                            expectedRevision);
            if (current.state()
                    == CredentialState.COMPROMISED) {
                return current;
            }
            if (current.state()
                    == CredentialState.REVOKED
                    || current.state()
                    == CredentialState.EXPIRED) {
                throw new IllegalStateException(
                        "terminal Credential cannot be compromised");
            }
            return repository.updateCredentialState(
                    tenant,
                    credentialId,
                    CredentialState.COMPROMISED,
                    expectedRevision,
                    now,
                    now,
                    null,
                    null);
        });
    }

    public Credential revoke(
            TenantContext tenant,
            UUID credentialId,
            long expectedRevision,
            Instant now) {
        return transactions.required(() -> {
            Credential current =
                    requireCurrent(
                            tenant,
                            credentialId,
                            expectedRevision);
            if (current.state()
                    == CredentialState.REVOKED) {
                return current;
            }
            if (current.state()
                    == CredentialState.EXPIRED) {
                throw new IllegalStateException(
                        "expired Credential is already terminal");
            }
            return repository.updateCredentialState(
                    tenant,
                    credentialId,
                    CredentialState.REVOKED,
                    expectedRevision,
                    now,
                    current.compromisedAt(),
                    now,
                    null);
        });
    }

    public Credential materializeBoundary(
            TenantContext tenant,
            UUID credentialId,
            Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            Credential current =
                    repository.findCredential(
                            tenant, credentialId)
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Credential does not exist"));

            if (current.state()
                    == CredentialState.REVOKED
                    || current.state()
                    == CredentialState.EXPIRED
                    || current.state()
                    == CredentialState.COMPROMISED) {
                return current;
            }

            if (current.validUntil() != null
                    && !current.validUntil().isAfter(now)) {
                return repository.updateCredentialState(
                        tenant,
                        credentialId,
                        CredentialState.EXPIRED,
                        current.revision(),
                        now,
                        null,
                        null,
                        now);
            }

            if (current.state()
                    == CredentialState.SCHEDULED
                    && current.validFrom() != null
                    && !now.isBefore(current.validFrom())) {
                return repository.updateCredentialState(
                        tenant,
                        credentialId,
                        CredentialState.ACTIVE,
                        current.revision(),
                        now,
                        null,
                        null,
                        null);
            }
            return current;
        });
    }

    private Credential requireCurrent(
            TenantContext tenant,
            UUID credentialId,
            long expectedRevision) {
        if (expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "expectedRevision must be positive");
        }
        Credential current = repository.findCredential(
                        tenant, credentialId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Credential does not exist"));
        if (current.revision() != expectedRevision) {
            throw new StaleWriteException(
                    "credential",
                    credentialId,
                    expectedRevision);
        }
        return current;
    }
}
