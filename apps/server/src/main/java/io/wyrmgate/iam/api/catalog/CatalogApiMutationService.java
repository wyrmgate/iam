package io.wyrmgate.iam.api.catalog;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.CatalogRepository;
import io.wyrmgate.iam.catalog.domain.Application;
import io.wyrmgate.iam.catalog.domain.ApplicationTarget;
import io.wyrmgate.iam.catalog.domain.Entitlement;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CatalogApiMutationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(CatalogApiMutationService.class);

    private final AdministrativeAuthorizationService authorization;
    private final CatalogCommandService commands;
    private final CatalogRepository repository;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    CatalogApiMutationService(
            AdministrativeAuthorizationService authorization,
            CatalogCommandService commands,
            CatalogRepository repository,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    Application createApplication(
            AuthenticatedAdministrativeActor actor,
            String code,
            String name,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(
                actor,
                "application",
                null,
                "application:create",
                now,
                correlationId,
                () -> transactions.required(() -> {
            require(actor, AdministrativePermissions.APPLICATION_CREATE,
                    AdministrativeResource.collection("application"), now, correlationId);
            Registration registration = register(
                    actor, "api.catalog.application.create.v1", key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayApplication(actor, registration, correlationId);
            }
            Application created = commands.createApplication(actor.tenant(), code, name, now);
            complete(actor, "api.catalog.application.create.v1", key, fingerprint,
                    "application", created.id(), now);
            return created;
        }),
                Application::id);
    }

    Application renameApplication(
            AuthenticatedAdministrativeActor actor,
            UUID id,
            String name,
            long revision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(
                actor,
                "application",
                id,
                "application:rename",
                now,
                correlationId,
                () -> transactions.required(() -> {
            require(actor, AdministrativePermissions.APPLICATION_UPDATE,
                    new AdministrativeResource("application", id), now, correlationId);
            ensureApplication(actor, id, correlationId);
            Registration registration = register(
                    actor, "api.catalog.application.rename.v1", key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayApplication(actor, registration, correlationId);
            }
            Application updated = commands.renameApplication(
                    actor.tenant(), id, name, revision, now);
            complete(actor, "api.catalog.application.rename.v1", key, fingerprint,
                    "application", updated.id(), now);
            return updated;
        }),
                Application::id);
    }

    Application retireApplication(
            AuthenticatedAdministrativeActor actor,
            UUID id,
            long revision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(
                actor,
                "application",
                id,
                "application:retire",
                now,
                correlationId,
                () -> transactions.required(() -> {
            require(actor, AdministrativePermissions.APPLICATION_RETIRE,
                    new AdministrativeResource("application", id), now, correlationId);
            ensureApplication(actor, id, correlationId);
            Registration registration = register(
                    actor, "api.catalog.application.retire.v1", key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayApplication(actor, registration, correlationId);
            }
            Application retired = commands.retireApplication(
                    actor.tenant(), id, revision, now);
            complete(actor, "api.catalog.application.retire.v1", key, fingerprint,
                    "application", retired.id(), now);
            return retired;
        }),
                Application::id);
    }

    ApplicationTarget createTarget(
            AuthenticatedAdministrativeActor actor,
            UUID applicationId,
            String code,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(
                actor,
                "application-target",
                null,
                "application-target:create",
                now,
                correlationId,
                () -> transactions.required(() -> {
            require(actor, AdministrativePermissions.APPLICATION_TARGET_CREATE,
                    AdministrativeResource.collection("application-target"), now, correlationId);
            ensureApplication(actor, applicationId, correlationId);
            Registration registration = register(
                    actor, "api.catalog.target.create.v1", key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayTarget(actor, registration, correlationId);
            }
            ApplicationTarget created =
                    commands.createTarget(actor.tenant(), applicationId, code, now);
            complete(actor, "api.catalog.target.create.v1", key, fingerprint,
                    "application-target", created.id(), now);
            return created;
        }),
                ApplicationTarget::id);
    }

    ApplicationTarget retireTarget(
            AuthenticatedAdministrativeActor actor,
            UUID id,
            long revision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(
                actor,
                "application-target",
                id,
                "application-target:retire",
                now,
                correlationId,
                () -> transactions.required(() -> {
            require(actor, AdministrativePermissions.APPLICATION_TARGET_RETIRE,
                    new AdministrativeResource("application-target", id), now, correlationId);
            ensureTarget(actor, id, correlationId);
            Registration registration = register(
                    actor, "api.catalog.target.retire.v1", key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayTarget(actor, registration, correlationId);
            }
            ApplicationTarget retired =
                    commands.retireTarget(actor.tenant(), id, revision, now);
            complete(actor, "api.catalog.target.retire.v1", key, fingerprint,
                    "application-target", retired.id(), now);
            return retired;
        }),
                ApplicationTarget::id);
    }

    Entitlement createEntitlement(
            AuthenticatedAdministrativeActor actor,
            UUID applicationId,
            UUID targetId,
            String code,
            String nativeKey,
            String type,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(
                actor,
                "entitlement",
                null,
                "entitlement:create",
                now,
                correlationId,
                () -> transactions.required(() -> {
            require(actor, AdministrativePermissions.ENTITLEMENT_CREATE,
                    AdministrativeResource.collection("entitlement"), now, correlationId);
            ensureApplication(actor, applicationId, correlationId);
            Registration registration = register(
                    actor, "api.catalog.entitlement.create.v1", key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayEntitlement(actor, registration, correlationId);
            }
            Entitlement created = commands.createEntitlement(
                    actor.tenant(), applicationId, targetId, code, nativeKey, type, now);
            complete(actor, "api.catalog.entitlement.create.v1", key, fingerprint,
                    "entitlement", created.id(), now);
            return created;
        }),
                Entitlement::id);
    }

    Entitlement retireEntitlement(
            AuthenticatedAdministrativeActor actor,
            UUID id,
            long revision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(
                actor,
                "entitlement",
                id,
                "entitlement:retire",
                now,
                correlationId,
                () -> transactions.required(() -> {
            require(actor, AdministrativePermissions.ENTITLEMENT_RETIRE,
                    new AdministrativeResource("entitlement", id), now, correlationId);
            ensureEntitlement(actor, id, correlationId);
            Registration registration = register(
                    actor, "api.catalog.entitlement.retire.v1", key, fingerprint, now);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replayEntitlement(actor, registration, correlationId);
            }
            Entitlement retired =
                    commands.retireEntitlement(actor.tenant(), id, revision, now);
            complete(actor, "api.catalog.entitlement.retire.v1", key, fingerprint,
                    "entitlement", retired.id(), now);
            return retired;
        }),
                Entitlement::id);
    }


    private <T> T audited(
            AuthenticatedAdministrativeActor actor,
            String resourceType,
            UUID resourceId,
            String actionType,
            Instant now,
            UUID correlationId,
            Supplier<T> operation,
            Function<T, UUID> resultId) {
        try {
            T result = operation.get();
            recordOutcome(
                    actor,
                    resourceType,
                    resourceId != null ? resourceId : resultId.apply(result),
                    actionType,
                    AuditOutcome.SUCCESS,
                    now,
                    correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    resourceType,
                    resourceId,
                    actionType,
                    auditOutcome(failure),
                    now,
                    correlationId);
            throw failure;
        }
    }

    private static AuditOutcome auditOutcome(RuntimeException failure) {
        return failure instanceof CatalogApiException api
                        && api.status() == org.springframework.http.HttpStatus.FORBIDDEN
                ? AuditOutcome.DENIED
                : AuditOutcome.FAILURE;
    }

    private void recordOutcome(
            AuthenticatedAdministrativeActor actor,
            String resourceType,
            UUID resourceId,
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
                            resourceType,
                            resourceId,
                            outcome,
                            correlationId,
                            null));
        } catch (RuntimeException auditFailure) {
            LOG.warn(
                    "Catalog AuditRecord append failed; correlationId={} actionType={} outcome={}",
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
        return idempotency.register(actor.tenant(), namespace, key, fingerprint, now, null);
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
                actor.tenant(), namespace, key, fingerprint, resourceType, resourceId, now);
    }

    private Application replayApplication(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        return replay(registration, "application", correlationId,
                () -> repository.findApplication(actor.tenant(), registration.resourceId())
                        .orElseThrow());
    }

    private ApplicationTarget replayTarget(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        return replay(registration, "application-target", correlationId,
                () -> repository.findTarget(actor.tenant(), registration.resourceId())
                        .orElseThrow());
    }

    private Entitlement replayEntitlement(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        return replay(registration, "entitlement", correlationId,
                () -> repository.findEntitlement(actor.tenant(), registration.resourceId())
                        .orElseThrow());
    }

    private static <T> T replay(
            Registration registration,
            String resourceType,
            UUID correlationId,
            Supplier<T> loader) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw CatalogApiException.conflict(
                    correlationId, "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!resourceType.equals(registration.resourceType())
                || registration.resourceId() == null) {
            throw new IllegalStateException("completed Catalog idempotency result is invalid");
        }
        return loader.get();
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(actor, permission, resource, now).allowed()) {
            throw CatalogApiException.forbidden(correlationId);
        }
    }

    private void ensureApplication(
            AuthenticatedAdministrativeActor actor, UUID id, UUID correlationId) {
        if (repository.findApplication(actor.tenant(), id).isEmpty()) {
            throw CatalogApiException.notFound(correlationId);
        }
    }

    private void ensureTarget(
            AuthenticatedAdministrativeActor actor, UUID id, UUID correlationId) {
        if (repository.findTarget(actor.tenant(), id).isEmpty()) {
            throw CatalogApiException.notFound(correlationId);
        }
    }

    private void ensureEntitlement(
            AuthenticatedAdministrativeActor actor, UUID id, UUID correlationId) {
        if (repository.findEntitlement(actor.tenant(), id).isEmpty()) {
            throw CatalogApiException.notFound(correlationId);
        }
    }
}
