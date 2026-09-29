package io.wyrmgate.iam.api.credential;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.credential.application.CredentialRepository;
import io.wyrmgate.iam.credential.application.CredentialRotationService;
import io.wyrmgate.iam.credential.application.CredentialService;
import io.wyrmgate.iam.credential.domain.CredentialModels.Credential;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialKind;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialRotation;
import io.wyrmgate.iam.credential.domain.CredentialModels.SecretReference;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class CredentialApiMutationService {

    private final AdministrativeAuthorizationService authorization;
    private final CredentialService credentials;
    private final CredentialRotationService rotations;
    private final CredentialRepository repository;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;

    CredentialApiMutationService(
            AdministrativeAuthorizationService authorization,
            CredentialService credentials,
            CredentialRotationService rotations,
            CredentialRepository repository,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
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
        return transactions.required(() -> {
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
        return transactions.required(() -> {
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
            Supplier<Credential> action) {
        return transactions.required(() -> {
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
