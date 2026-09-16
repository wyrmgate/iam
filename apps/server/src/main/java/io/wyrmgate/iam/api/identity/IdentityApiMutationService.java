package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** HTTP-adapter orchestration for authorization + causal idempotency + Identity commands. */
final class IdentityApiMutationService {

    private static final String CREATE_NAMESPACE = "api.identity.create.v1";
    private static final String UPDATE_NAMESPACE = "api.identity.update-metadata.v1";

    private final AdministrativeAuthorizationService authorization;
    private final IdentityCommandService commands;
    private final IdentityRepository identities;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;

    IdentityApiMutationService(
            AdministrativeAuthorizationService authorization,
            IdentityCommandService commands,
            IdentityRepository identities,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    Identity create(
            AuthenticatedAdministrativeActor actor,
            IdentityType type,
            IdentityProfile profile,
            IdentityLifecycleState lifecycleState,
            String displayName,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            requireAllowed(actor, AdministrativeResource.collection("identity"), true, now, correlationId);
            var registration = idempotency.register(
                    actor.tenant(), CREATE_NAMESPACE, idempotencyKey, fingerprint, now, null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replay(actor, registration, correlationId);
            }
            Identity created = commands.create(
                    actor.tenant(), type, profile, lifecycleState, displayName, now, correlationId, null);
            idempotency.complete(
                    actor.tenant(), CREATE_NAMESPACE, idempotencyKey, fingerprint,
                    "identity", created.id(), now);
            return created;
        });
    }

    Identity updateDisplayName(
            AuthenticatedAdministrativeActor actor,
            UUID identityId,
            String displayName,
            long expectedRevision,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return transactions.required(() -> {
            requireAllowed(actor, new AdministrativeResource("identity", identityId), false, now, correlationId);
            if (identities.findById(actor.tenant(), identityId).isEmpty()) {
                throw IdentityApiException.notFound(correlationId);
            }
            var registration = idempotency.register(
                    actor.tenant(), UPDATE_NAMESPACE, idempotencyKey, fingerprint, now, null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replay(actor, registration, correlationId);
            }
            Identity updated = commands.changeDisplayName(
                    actor.tenant(), identityId, displayName, expectedRevision, now, correlationId, null);
            idempotency.complete(
                    actor.tenant(), UPDATE_NAMESPACE, idempotencyKey, fingerprint,
                    "identity", updated.id(), now);
            return updated;
        });
    }

    private void requireAllowed(
            AuthenticatedAdministrativeActor actor,
            AdministrativeResource resource,
            boolean create,
            Instant now,
            UUID correlationId) {
        var permission = create ? AdministrativePermissions.IDENTITY_CREATE : AdministrativePermissions.IDENTITY_UPDATE;
        if (!authorization.authorize(actor, permission, resource, now).allowed()) {
            throw IdentityApiException.forbidden(correlationId);
        }
    }

    private Identity replay(
            AuthenticatedAdministrativeActor actor,
            JdbcIdempotencyRepository.Registration registration,
            UUID correlationId) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw IdentityApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!"identity".equals(registration.resourceType()) || registration.resourceId() == null) {
            throw new IllegalStateException("completed Identity idempotency record has no Identity result");
        }
        return identities.findById(actor.tenant(), registration.resourceId())
                .orElseThrow(() -> new IllegalStateException("idempotent Identity result no longer exists"));
    }
}
