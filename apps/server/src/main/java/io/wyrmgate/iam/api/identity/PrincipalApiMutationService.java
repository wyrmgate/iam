package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.identity.application.PrincipalCommandException;
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.identity.application.PrincipalRepository;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

final class PrincipalApiMutationService {

    private static final String REGISTER_NAMESPACE =
            "api.principal.register.v1";
    private static final String CORRELATE_NAMESPACE =
            "api.principal.correlate.v1";

    private final AdministrativeAuthorizationService authorization;
    private final PrincipalCommandService commands;
    private final PrincipalRepository principals;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;

    PrincipalApiMutationService(
            AdministrativeAuthorizationService authorization,
            PrincipalCommandService commands,
            PrincipalRepository principals,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        this.authorization = Objects.requireNonNull(
                authorization, "authorization");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.principals = Objects.requireNonNull(
                principals, "principals");
        this.idempotency = Objects.requireNonNull(
                idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    Principal register(
            AuthenticatedAdministrativeActor actor,
            UUID applicationTargetId,
            String nativePrincipalKey,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            require(
                    actor,
                    AdministrativePermissions.PRINCIPAL_REGISTER,
                    AdministrativeResource.collection("principal"),
                    now,
                    correlationId);
            Registration registration = idempotency.register(
                    actor.tenant(),
                    REGISTER_NAMESPACE,
                    idempotencyKey,
                    fingerprint,
                    now,
                    null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replay(actor, registration, correlationId);
            }

            Principal created;
            try {
                created = commands.create(
                        actor.tenant(),
                        applicationTargetId,
                        nativePrincipalKey,
                        null,
                        now,
                        correlationId,
                        null);
            } catch (PrincipalCommandException error) {
                throw semantic(error, correlationId);
            }

            idempotency.complete(
                    actor.tenant(),
                    REGISTER_NAMESPACE,
                    idempotencyKey,
                    fingerprint,
                    "principal",
                    created.id(),
                    now);
            return created;
        });
    }

    Principal correlate(
            AuthenticatedAdministrativeActor actor,
            UUID principalId,
            UUID identityId,
            long expectedRevision,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            require(
                    actor,
                    AdministrativePermissions.PRINCIPAL_CORRELATE,
                    new AdministrativeResource(
                            "principal", principalId),
                    now,
                    correlationId);
            if (principals.findById(
                    actor.tenant(), principalId).isEmpty()) {
                throw IdentityApiException.notFound(
                        correlationId);
            }

            Registration registration = idempotency.register(
                    actor.tenant(),
                    CORRELATE_NAMESPACE,
                    idempotencyKey,
                    fingerprint,
                    now,
                    null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replay(actor, registration, correlationId);
            }

            Principal updated;
            try {
                updated = commands.correlate(
                        actor.tenant(),
                        principalId,
                        identityId,
                        expectedRevision,
                        now,
                        correlationId,
                        null);
            } catch (PrincipalCommandException error) {
                throw semantic(error, correlationId);
            }

            idempotency.complete(
                    actor.tenant(),
                    CORRELATE_NAMESPACE,
                    idempotencyKey,
                    fingerprint,
                    "principal",
                    updated.id(),
                    now);
            return updated;
        });
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(
                actor, permission, resource, now).allowed()) {
            throw IdentityApiException.forbidden(correlationId);
        }
    }

    private Principal replay(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        if (!"COMPLETED".equals(
                registration.operationState())) {
            throw IdentityApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!"principal".equals(registration.resourceType())
                || registration.resourceId() == null) {
            throw new IllegalStateException(
                    "completed Principal idempotency result is invalid");
        }
        return principals.findById(
                        actor.tenant(),
                        registration.resourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "idempotent Principal result no longer exists"));
    }

    private static IdentityApiException semantic(
            PrincipalCommandException error,
            UUID correlationId) {
        return switch (error.code()) {
            case "identity_not_found",
                    "application_target_not_found",
                    "principal_not_found" ->
                    IdentityApiException.notFound(correlationId);
            case "principal_conflict",
                    "application_target_retired",
                    "principal_already_correlated",
                    "principal_identity_conflict" ->
                    IdentityApiException.conflict(
                            correlationId,
                            error.code(),
                            error.getMessage());
            default -> IdentityApiException.conflict(
                    correlationId,
                    "principal_operation_conflict",
                    "The Principal operation is not valid in the current state.");
        };
    }
}
