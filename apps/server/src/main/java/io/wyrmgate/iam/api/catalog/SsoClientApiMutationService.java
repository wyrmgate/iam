package io.wyrmgate.iam.api.catalog;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.catalog.application.CatalogRepository;
import io.wyrmgate.iam.catalog.application.SsoClientRegistrationService;
import io.wyrmgate.iam.catalog.domain.SsoClientRegistration;
import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SsoClientApiMutationService {

    private static final Logger LOG = LoggerFactory.getLogger(SsoClientApiMutationService.class);
    private static final String RESOURCE_TYPE = "sso-client";

    private final AdministrativeAuthorizationService authorization;
    private final CatalogRepository catalog;
    private final SsoClientRegistrationService service;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    SsoClientApiMutationService(
            AdministrativeAuthorizationService authorization,
            CatalogRepository catalog,
            SsoClientRegistrationService service,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.service = Objects.requireNonNull(service, "service");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    SsoClientRegistration create(
            AuthenticatedAdministrativeActor actor,
            UUID applicationId,
            Set<String> redirectUris,
            Set<SsoClientScope> scopes,
            boolean requiresGovernedAccess,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(actor, null, "sso-client:create", now, correlationId, () -> transactions.required(() -> {
            require(actor, AdministrativePermissions.SSO_CLIENT_CREATE,
                    AdministrativeResource.collection(RESOURCE_TYPE), now, correlationId);
            ensureApplication(actor, applicationId, correlationId);
            Registration registration = idempotency.register(
                    actor.tenant(), "api.catalog.sso-client.create.v1", idempotencyKey, fingerprint, now, null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replay(actor, registration, correlationId);
            }
            SsoClientRegistration created = service.create(
                    actor.tenant(), applicationId, redirectUris, scopes, requiresGovernedAccess, now);
            idempotency.complete(
                    actor.tenant(), "api.catalog.sso-client.create.v1", idempotencyKey, fingerprint,
                    RESOURCE_TYPE, created.id(), now);
            return created;
        }));
    }

    SsoClientRegistration replace(
            AuthenticatedAdministrativeActor actor,
            UUID registrationId,
            Set<String> redirectUris,
            Set<SsoClientScope> scopes,
            boolean requiresGovernedAccess,
            long expectedRevision,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(actor, registrationId, "sso-client:update", now, correlationId, () -> transactions.required(() -> {
            require(actor, AdministrativePermissions.SSO_CLIENT_UPDATE,
                    new AdministrativeResource(RESOURCE_TYPE, registrationId), now, correlationId);
            ensureRegistration(actor, registrationId, correlationId);
            Registration registration = idempotency.register(
                    actor.tenant(), "api.catalog.sso-client.update.v1", idempotencyKey, fingerprint, now, null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replay(actor, registration, correlationId);
            }
            SsoClientRegistration updated = service.replaceConfiguration(
                    actor.tenant(), registrationId, redirectUris, scopes,
                    requiresGovernedAccess, expectedRevision, now);
            idempotency.complete(
                    actor.tenant(), "api.catalog.sso-client.update.v1", idempotencyKey, fingerprint,
                    RESOURCE_TYPE, updated.id(), now);
            return updated;
        }));
    }

    SsoClientRegistration retire(
            AuthenticatedAdministrativeActor actor,
            UUID registrationId,
            long expectedRevision,
            String idempotencyKey,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(actor, registrationId, "sso-client:retire", now, correlationId, () -> transactions.required(() -> {
            require(actor, AdministrativePermissions.SSO_CLIENT_RETIRE,
                    new AdministrativeResource(RESOURCE_TYPE, registrationId), now, correlationId);
            ensureRegistration(actor, registrationId, correlationId);
            Registration registration = idempotency.register(
                    actor.tenant(), "api.catalog.sso-client.retire.v1", idempotencyKey, fingerprint, now, null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                return replay(actor, registration, correlationId);
            }
            SsoClientRegistration retired = service.retire(
                    actor.tenant(), registrationId, expectedRevision, now);
            idempotency.complete(
                    actor.tenant(), "api.catalog.sso-client.retire.v1", idempotencyKey, fingerprint,
                    RESOURCE_TYPE, retired.id(), now);
            return retired;
        }));
    }

    private SsoClientRegistration replay(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw CatalogApiException.conflict(
                    correlationId, "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!RESOURCE_TYPE.equals(registration.resourceType()) || registration.resourceId() == null) {
            throw new IllegalStateException("completed SSO client idempotency result is invalid");
        }
        return service.find(actor.tenant(), registration.resourceId()).orElseThrow();
    }

    private void ensureApplication(
            AuthenticatedAdministrativeActor actor, UUID applicationId, UUID correlationId) {
        if (catalog.findApplication(actor.tenant(), applicationId).isEmpty()) {
            throw CatalogApiException.notFound(correlationId);
        }
    }

    private void ensureRegistration(
            AuthenticatedAdministrativeActor actor, UUID registrationId, UUID correlationId) {
        if (service.find(actor.tenant(), registrationId).isEmpty()) {
            throw CatalogApiException.notFound(correlationId);
        }
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

    private SsoClientRegistration audited(
            AuthenticatedAdministrativeActor actor,
            UUID resourceId,
            String action,
            Instant occurredAt,
            UUID correlationId,
            Supplier<SsoClientRegistration> operation) {
        try {
            SsoClientRegistration result = operation.get();
            record(actor, resourceId != null ? resourceId : result.id(), action,
                    AuditOutcome.SUCCESS, occurredAt, correlationId);
            return result;
        } catch (RuntimeException failure) {
            record(actor, resourceId, action,
                    failure instanceof CatalogApiException api
                                    && api.status() == org.springframework.http.HttpStatus.FORBIDDEN
                            ? AuditOutcome.DENIED : AuditOutcome.FAILURE,
                    occurredAt, correlationId);
            throw failure;
        }
    }

    private void record(
            AuthenticatedAdministrativeActor actor,
            UUID resourceId,
            String action,
            AuditOutcome outcome,
            Instant occurredAt,
            UUID correlationId) {
        try {
            audit.append(actor.tenant(), new AuditRecordDraft(
                    ids.nextId(), occurredAt, actor.identityId(), action,
                    RESOURCE_TYPE, resourceId, outcome, correlationId, null));
        } catch (RuntimeException auditFailure) {
            LOG.warn("SSO client AuditRecord append failed; correlationId={} action={} outcome={}",
                    correlationId, action, outcome);
        }
    }
}
