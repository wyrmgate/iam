package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HTTP-adapter orchestration for authorization + causal idempotency + Identity commands. */
final class IdentityApiMutationService {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityApiMutationService.class);

    private static final String CREATE_NAMESPACE = "api.identity.create.v1";
    private static final String UPDATE_NAMESPACE = "api.identity.update-metadata.v1";
    private static final String ACTIVATE_NAMESPACE = "api.identity.activate.v1";
    private static final String SUSPEND_NAMESPACE = "api.identity.suspend.v1";
    private static final String DEACTIVATE_NAMESPACE = "api.identity.deactivate.v1";
    private static final String DECOMMISSION_NAMESPACE = "api.identity.decommission.v1";

    private final AdministrativeAuthorizationService authorization;
    private final IdentityCommandService commands;
    private final IdentityRepository identities;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    IdentityApiMutationService(
            AdministrativeAuthorizationService authorization,
            IdentityCommandService commands,
            IdentityRepository identities,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
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

    Identity changeLifecycle(
            AuthenticatedAdministrativeActor actor,
            UUID identityId,
            IdentityLifecycleState targetState,
            long expectedRevision,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        var operation = lifecycleOperation(targetState);
        try {
            Identity result = transactions.required(() -> {
            requireAllowed(
                    actor,
                    operation.permission(),
                    new AdministrativeResource("identity", identityId),
                    now,
                    correlationId);
            if (identities.findById(actor.tenant(), identityId).isEmpty()) {
                throw IdentityApiException.notFound(correlationId);
            }
            var registration = idempotency.register(
                    actor.tenant(),
                    operation.namespace(),
                    idempotencyKey,
                    fingerprint,
                    now,
                    null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replay(actor, registration, correlationId);
            }

            Identity updated;
            try {
                updated = commands.changeLifecycle(
                        actor.tenant(),
                        identityId,
                        targetState,
                        expectedRevision,
                        now,
                        correlationId,
                        null);
            } catch (IllegalArgumentException | IllegalStateException invalidTransition) {
                throw IdentityApiException.conflict(
                        correlationId,
                        "invalid_lifecycle_transition",
                        "The requested Identity lifecycle transition is not permitted.");
            }

            idempotency.complete(
                    actor.tenant(),
                    operation.namespace(),
                    idempotencyKey,
                    fingerprint,
                    "identity",
                    updated.id(),
                    now);
                return updated;
            });
            recordLifecycleOutcome(
                    actor, identityId, operation.auditAction(), AuditOutcome.SUCCESS, now, correlationId);
            return result;
        } catch (RuntimeException failure) {
            AuditOutcome outcome = failure instanceof IdentityApiException api
                            && api.status() == org.springframework.http.HttpStatus.FORBIDDEN
                    ? AuditOutcome.DENIED
                    : AuditOutcome.FAILURE;
            recordLifecycleOutcome(actor, identityId, operation.auditAction(), outcome, now, correlationId);
            throw failure;
        }
    }

    private void recordLifecycleOutcome(
            AuthenticatedAdministrativeActor actor,
            UUID identityId,
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
                            "identity",
                            identityId,
                            outcome,
                            correlationId,
                            null));
        } catch (RuntimeException auditFailure) {
            LOG.warn(
                    "Identity lifecycle AuditRecord append failed; correlationId={} actionType={} outcome={}",
                    correlationId,
                    actionType,
                    outcome);
        }
    }

    private static LifecycleOperation lifecycleOperation(
            IdentityLifecycleState targetState) {
        return switch (targetState) {
            case ACTIVE -> new LifecycleOperation(
                    ACTIVATE_NAMESPACE,
                    AdministrativePermissions.IDENTITY_ACTIVATE,
                    "identity:activate");
            case SUSPENDED -> new LifecycleOperation(
                    SUSPEND_NAMESPACE,
                    AdministrativePermissions.IDENTITY_SUSPEND,
                    "identity:suspend");
            case INACTIVE -> new LifecycleOperation(
                    DEACTIVATE_NAMESPACE,
                    AdministrativePermissions.IDENTITY_DEACTIVATE,
                    "identity:deactivate");
            case DECOMMISSIONED -> new LifecycleOperation(
                    DECOMMISSION_NAMESPACE,
                    AdministrativePermissions.IDENTITY_DECOMMISSION,
                    "identity:decommission");
            case PENDING -> throw new IllegalArgumentException(
                    "PENDING is not a public lifecycle command target");
        };
    }

    private void requireAllowed(
            AuthenticatedAdministrativeActor actor,
            AdministrativeResource resource,
            boolean create,
            Instant now,
            UUID correlationId) {
        var permission = create ? AdministrativePermissions.IDENTITY_CREATE : AdministrativePermissions.IDENTITY_UPDATE;
        requireAllowed(actor, permission, resource, now, correlationId);
    }

    private void requireAllowed(
            AuthenticatedAdministrativeActor actor,
            io.wyrmgate.iam.administration.domain.AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(actor, permission, resource, now).allowed()) {
            throw IdentityApiException.forbidden(correlationId);
        }
    }

    private record LifecycleOperation(
            String namespace,
            io.wyrmgate.iam.administration.domain.AdministrativePermission permission,
            String auditAction) {
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
