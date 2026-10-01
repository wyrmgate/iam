package io.wyrmgate.iam.api.credential;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.credential.application.CredentialRepository;
import io.wyrmgate.iam.credential.application.CredentialRotationService;
import io.wyrmgate.iam.credential.application.CredentialService;
import io.wyrmgate.iam.credential.domain.CredentialModels.Credential;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialKind;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialRotation;
import io.wyrmgate.iam.credential.domain.CredentialModels.SecretReference;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CredentialApiMutationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(CredentialApiMutationService.class);

    private final AdministrativeAuthorizationService authorization;
    private final CredentialService credentials;
    private final CredentialRotationService rotations;
    private final CredentialRepository repository;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    CredentialApiMutationService(
            AdministrativeAuthorizationService authorization,
            CredentialService credentials,
            CredentialRotationService rotations,
            CredentialRepository repository,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authorization = Objects.requireNonNull(
                authorization, "authorization");
        this.credentials = Objects.requireNonNull(
                credentials, "credentials");
        this.rotations = Objects.requireNonNull(
                rotations, "rotations");
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.idempotency = Objects.requireNonNull(
                idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    Credential create(
            AuthenticatedAdministrativeActor actor,
            UUID principalId,
            CredentialKind kind,
            SecretReference secretReference,
            Instant validFrom,
            Instant validUntil,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        try {
            Credential result = transactions.required(() -> {
                require(
                        actor,
                        AdministrativePermissions.CREDENTIAL_CREATE,
                        AdministrativeResource.collection("credential"),
                        now,
                        correlationId);
                Registration registration = register(
                        actor,
                        "api.credential.create.v1",
                        key,
                        fingerprint,
                        now);
                if (registration.kind() == RegistrationKind.REPLAY) {
                    return replayCredential(
                            actor, registration, correlationId);
                }
                Credential created = credentials.create(
                        actor.tenant(),
                        principalId,
                        kind,
                        secretReference,
                        validFrom,
                        validUntil,
                        now);
                complete(
                        actor,
                        "api.credential.create.v1",
                        key,
                        fingerprint,
                        "credential",
                        created.id(),
                        now);
                return created;
            });
            recordOutcome(
                    actor,
                    result.id(),
                    "credential:create",
                    AuditOutcome.SUCCESS,
                    now,
                    correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    null,
                    "credential:create",
                    auditOutcome(failure),
                    now,
                    correlationId);
            throw failure;
        }
    }

    Credential revoke(
            AuthenticatedAdministrativeActor actor,
            UUID credentialId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return lifecycle(
                actor,
                credentialId,
                expectedRevision,
                key,
                fingerprint,
                now,
                correlationId,
                AdministrativePermissions.CREDENTIAL_REVOKE,
                "api.credential.revoke.v1",
                "credential:revoke",
                () -> credentials.revoke(
                        actor.tenant(),
                        credentialId,
                        expectedRevision,
                        now));
    }

    Credential compromise(
            AuthenticatedAdministrativeActor actor,
            UUID credentialId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return lifecycle(
                actor,
                credentialId,
                expectedRevision,
                key,
                fingerprint,
                now,
                correlationId,
                AdministrativePermissions.CREDENTIAL_COMPROMISE,
                "api.credential.compromise.v1",
                "credential:compromise",
                () -> credentials.compromise(
                        actor.tenant(),
                        credentialId,
                        expectedRevision,
                        now));
    }

    CredentialRotation rotate(
            AuthenticatedAdministrativeActor actor,
            UUID credentialId,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        try {
            CredentialRotation result = transactions.required(() -> {
                require(
                        actor,
                        AdministrativePermissions.CREDENTIAL_ROTATE,
                        new AdministrativeResource(
                                "credential", credentialId),
                        now,
                        correlationId);
                ensureCredential(
                        actor, credentialId, correlationId);
                Registration registration = register(
                        actor,
                        "api.credential.rotate.v1",
                        key,
                        fingerprint,
                        now);
                if (registration.kind() == RegistrationKind.REPLAY) {
                    return replayRotation(
                            actor, registration, correlationId);
                }
                CredentialRotation created;
                try {
                    created = rotations.plan(
                            actor.tenant(),
                            credentialId,
                            actor.identityId(),
                            now);
                } catch (IllegalStateException invalidState) {
                    throw invalidState(
                            correlationId);
                }
                complete(
                        actor,
                        "api.credential.rotate.v1",
                        key,
                        fingerprint,
                        "credential-rotation",
                        created.id(),
                        now);
                return created;
            });
            recordOutcome(
                    actor,
                    credentialId,
                    "credential:rotate",
                    AuditOutcome.SUCCESS,
                    now,
                    correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    credentialId,
                    "credential:rotate",
                    auditOutcome(failure),
                    now,
                    correlationId);
            throw failure;
        }
    }

    private Credential lifecycle(
            AuthenticatedAdministrativeActor actor,
            UUID credentialId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId,
            AdministrativePermission permission,
            String namespace,
            String auditAction,
            Supplier<Credential> action) {
        try {
            Credential result = transactions.required(() -> {
            require(
                    actor,
                    permission,
                    new AdministrativeResource(
                            "credential", credentialId),
                    now,
                    correlationId);
            ensureCredential(
                    actor, credentialId, correlationId);
            Registration registration = register(
                    actor,
                    namespace,
                    key,
                    fingerprint,
                    now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayCredential(
                        actor, registration, correlationId);
            }
            Credential updated;
            try {
                updated = action.get();
            } catch (IllegalStateException invalidState) {
                throw invalidState(
                        correlationId);
            }
            complete(
                    actor,
                    namespace,
                    key,
                    fingerprint,
                    "credential",
                    updated.id(),
                    now);
                return updated;
            });
            recordOutcome(
                    actor,
                    credentialId,
                    auditAction,
                    AuditOutcome.SUCCESS,
                    now,
                    correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    credentialId,
                    auditAction,
                    auditOutcome(failure),
                    now,
                    correlationId);
            throw failure;
        }
    }

    private static AuditOutcome auditOutcome(RuntimeException failure) {
        return failure instanceof CredentialApiException api
                        && api.status() == org.springframework.http.HttpStatus.FORBIDDEN
                ? AuditOutcome.DENIED
                : AuditOutcome.FAILURE;
    }

    private void recordOutcome(
            AuthenticatedAdministrativeActor actor,
            UUID credentialId,
            String actionType,
            AuditOutcome outcome,
            Instant occurredAt,
            UUID correlationId) {
        try {
            audit.append(
                    actor.tenant(),
                    new AuditRecordDraft(
                            ids.nextId(),
                            occurredAt,
                            actor.identityId(),
                            actionType,
                            "credential",
                            credentialId,
                            outcome,
                            correlationId,
                            null));
        } catch (RuntimeException auditFailure) {
            LOG.warn(
                    "Credential AuditRecord append failed; correlationId={} actionType={} outcome={}",
                    correlationId,
                    actionType,
                    outcome);
        }
    }

    private Registration register(
            AuthenticatedAdministrativeActor actor,
            String namespace,
            String key,
            RequestFingerprint fingerprint,
            Instant now) {
        return idempotency.register(
                actor.tenant(),
                namespace,
                key,
                fingerprint,
                now,
                null);
    }

    private void complete(
            AuthenticatedAdministrativeActor actor,
            String namespace,
            String key,
            RequestFingerprint fingerprint,
            String resourceType,
            UUID resourceId,
            Instant now) {
        idempotency.complete(
                actor.tenant(),
                namespace,
                key,
                fingerprint,
                resourceType,
                resourceId,
                now);
    }

    private Credential replayCredential(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        requireCompleted(
                registration,
                "credential",
                correlationId);
        return repository.findCredential(
                        actor.tenant(),
                        registration.resourceId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "idempotent Credential result no longer exists"));
    }

    private CredentialRotation replayRotation(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        requireCompleted(
                registration,
                "credential-rotation",
                correlationId);
        return repository.findRotation(
                        actor.tenant(),
                        registration.resourceId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "idempotent CredentialRotation result no longer exists"));
    }

    private static void requireCompleted(
            Registration registration,
            String resourceType,
            UUID correlationId) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw CredentialApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!resourceType.equals(registration.resourceType())
                || registration.resourceId() == null) {
            throw new IllegalStateException(
                    "completed Credential idempotency result is invalid");
        }
    }

    private static CredentialApiException invalidState(
            UUID correlationId) {
        return CredentialApiException.conflict(
                correlationId,
                "invalid_state",
                "The Credential operation is not valid in the current state.");
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(
                actor, permission, resource, now).allowed()) {
            throw CredentialApiException.forbidden(correlationId);
        }
    }

    private void ensureCredential(
            AuthenticatedAdministrativeActor actor,
            UUID credentialId,
            UUID correlationId) {
        if (repository.findCredential(
                actor.tenant(), credentialId).isEmpty()) {
            throw CredentialApiException.notFound(correlationId);
        }
    }
}
