package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.identity.application.PrincipalCommandException;
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.identity.application.PrincipalRepository;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PrincipalApiMutationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(PrincipalApiMutationService.class);

    private static final String REGISTER_NAMESPACE =
            "api.principal.register.v1";
    private static final String CORRELATE_NAMESPACE =
            "api.principal.correlate.v1";

    private final AdministrativeAuthorizationService authorization;
    private final PrincipalCommandService commands;
    private final PrincipalRepository principals;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    PrincipalApiMutationService(
            AdministrativeAuthorizationService authorization,
            PrincipalCommandService commands,
            PrincipalRepository principals,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authorization = Objects.requireNonNull(
                authorization, "authorization");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.principals = Objects.requireNonNull(
                principals, "principals");
        this.idempotency = Objects.requireNonNull(
                idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    Principal register(
            AuthenticatedAdministrativeActor actor,
            UUID applicationTargetId,
            String nativePrincipalKey,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        try {
            Principal result = transactions.required(() -> {
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
            recordOutcome(
                    actor,
                    result.id(),
                    "principal:register",
                    AuditOutcome.SUCCESS,
                    now,
                    correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    null,
                    "principal:register",
                    auditOutcome(failure),
                    now,
                    correlationId);
            throw failure;
        }
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
        try {
            Principal result = transactions.required(() -> {
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
            recordOutcome(
                    actor,
                    principalId,
                    "principal:correlate",
                    AuditOutcome.SUCCESS,
                    now,
                    correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    principalId,
                    "principal:correlate",
                    auditOutcome(failure),
                    now,
                    correlationId);
            throw failure;
        }
    }

    private static AuditOutcome auditOutcome(RuntimeException failure) {
        return failure instanceof IdentityApiException api
                        && api.status() == org.springframework.http.HttpStatus.FORBIDDEN
                ? AuditOutcome.DENIED
                : AuditOutcome.FAILURE;
    }

    private void recordOutcome(
            AuthenticatedAdministrativeActor actor,
            UUID principalId,
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
                            "principal",
                            principalId,
                            outcome,
                            correlationId,
                            null));
        } catch (RuntimeException auditFailure) {
            LOG.warn(
                    "Principal AuditRecord append failed; correlationId={} actionType={} outcome={}",
                    correlationId,
                    actionType,
                    outcome);
        }
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
