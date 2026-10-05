package io.wyrmgate.iam.api.authentication;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AuthenticationAdministrativePermissions;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.authentication.application.AuthenticationRepository;
import io.wyrmgate.iam.authentication.application.AuthenticationService;
import io.wyrmgate.iam.authentication.domain.AuthenticationClient;
import io.wyrmgate.iam.authentication.domain.AuthenticationLoginBinding;
import io.wyrmgate.iam.authentication.domain.AuthenticationSession;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AuthenticationApiMutationService {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationApiMutationService.class);

    private final AdministrativeAuthorizationService authorization;
    private final AuthenticationService authentication;
    private final AuthenticationRepository repository;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    AuthenticationApiMutationService(
            AdministrativeAuthorizationService authorization,
            AuthenticationService authentication,
            AuthenticationRepository repository,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    AuthenticationClient createClient(
            AuthenticatedAdministrativeActor actor,
            String displayName,
            AuthenticationClient.ClientType clientType,
            List<URI> redirectUris,
            List<URI> postLogoutRedirectUris,
            Set<String> scopes,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(actor, "authentication-client", null, "authentication-client:create", now,
                correlationId, () -> transactions.required(() -> {
                    require(actor, AuthenticationAdministrativePermissions.CLIENT_CREATE,
                            AdministrativeResource.collection("authentication-client"), now, correlationId);
                    Registration registration = register(
                            actor, "api.authentication.client.create.v1", key, fingerprint, now);
                    if (registration.kind() == RegistrationKind.REPLAY) {
                        return replayClient(actor, registration, correlationId);
                    }
                    AuthenticationClient created = authentication.createClient(
                            actor.tenant(), displayName, clientType, redirectUris,
                            postLogoutRedirectUris, scopes, now);
                    complete(actor, "api.authentication.client.create.v1", key, fingerprint,
                            "authentication-client", created.id(), now);
                    return created;
                }), AuthenticationClient::id);
    }

    AuthenticationClient updateClient(
            AuthenticatedAdministrativeActor actor,
            UUID id,
            String displayName,
            List<URI> redirectUris,
            List<URI> postLogoutRedirectUris,
            Set<String> scopes,
            long revision,
            Instant now,
            UUID correlationId) {
        return audited(actor, "authentication-client", id, "authentication-client:update", now,
                correlationId, () -> {
                    require(actor, AuthenticationAdministrativePermissions.CLIENT_UPDATE,
                            new AdministrativeResource("authentication-client", id), now, correlationId);
                    ensureClient(actor, id, correlationId);
                    return authentication.updateClient(
                            actor.tenant(), id, displayName, redirectUris,
                            postLogoutRedirectUris, scopes, revision, now);
                }, AuthenticationClient::id);
    }

    AuthenticationClient disableClient(
            AuthenticatedAdministrativeActor actor,
            UUID id,
            long revision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(actor, "authentication-client", id, "authentication-client:disable", now,
                correlationId, () -> transactions.required(() -> {
                    require(actor, AuthenticationAdministrativePermissions.CLIENT_DISABLE,
                            new AdministrativeResource("authentication-client", id), now, correlationId);
                    ensureClient(actor, id, correlationId);
                    Registration registration = register(
                            actor, "api.authentication.client.disable.v1", key, fingerprint, now);
                    if (registration.kind() == RegistrationKind.REPLAY) {
                        return replayClient(actor, registration, correlationId);
                    }
                    AuthenticationClient disabled = authentication.disableClient(
                            actor.tenant(), id, revision, now);
                    complete(actor, "api.authentication.client.disable.v1", key, fingerprint,
                            "authentication-client", disabled.id(), now);
                    return disabled;
                }), AuthenticationClient::id);
    }

    AuthenticationLoginBinding createLoginBinding(
            AuthenticatedAdministrativeActor actor,
            UUID principalId,
            String loginIdentifier,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(actor, "authentication-login-binding", null,
                "authentication-login-binding:create", now, correlationId,
                () -> transactions.required(() -> {
                    require(actor, AuthenticationAdministrativePermissions.LOGIN_BINDING_CREATE,
                            AdministrativeResource.collection("authentication-login-binding"),
                            now, correlationId);
                    Registration registration = register(
                            actor, "api.authentication.login-binding.create.v1", key, fingerprint, now);
                    if (registration.kind() == RegistrationKind.REPLAY) {
                        return replayLoginBinding(actor, registration, correlationId);
                    }
                    AuthenticationLoginBinding created = authentication.createLoginBinding(
                            actor.tenant(), principalId, loginIdentifier, now);
                    complete(actor, "api.authentication.login-binding.create.v1", key, fingerprint,
                            "authentication-login-binding", created.id(), now);
                    return created;
                }), AuthenticationLoginBinding::id);
    }

    AuthenticationLoginBinding updateLoginBinding(
            AuthenticatedAdministrativeActor actor,
            UUID id,
            String loginIdentifier,
            long revision,
            Instant now,
            UUID correlationId) {
        return audited(actor, "authentication-login-binding", id,
                "authentication-login-binding:update", now, correlationId,
                () -> {
                    require(actor, AuthenticationAdministrativePermissions.LOGIN_BINDING_UPDATE,
                            new AdministrativeResource("authentication-login-binding", id),
                            now, correlationId);
                    ensureLoginBinding(actor, id, correlationId);
                    return authentication.updateLoginBindingDisplay(
                            actor.tenant(), id, loginIdentifier, revision, now);
                }, AuthenticationLoginBinding::id);
    }

    AuthenticationLoginBinding disableLoginBinding(
            AuthenticatedAdministrativeActor actor,
            UUID id,
            long revision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(actor, "authentication-login-binding", id,
                "authentication-login-binding:disable", now, correlationId,
                () -> transactions.required(() -> {
                    require(actor, AuthenticationAdministrativePermissions.LOGIN_BINDING_DISABLE,
                            new AdministrativeResource("authentication-login-binding", id),
                            now, correlationId);
                    ensureLoginBinding(actor, id, correlationId);
                    Registration registration = register(
                            actor, "api.authentication.login-binding.disable.v1", key, fingerprint, now);
                    if (registration.kind() == RegistrationKind.REPLAY) {
                        return replayLoginBinding(actor, registration, correlationId);
                    }
                    AuthenticationLoginBinding disabled = authentication.disableLoginBinding(
                            actor.tenant(), id, revision, now);
                    complete(actor, "api.authentication.login-binding.disable.v1", key, fingerprint,
                            "authentication-login-binding", disabled.id(), now);
                    return disabled;
                }), AuthenticationLoginBinding::id);
    }

    AuthenticationSession revokeSession(
            AuthenticatedAdministrativeActor actor,
            UUID id,
            long revision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        return audited(actor, "authentication-session", id,
                "authentication-session:revoke", now, correlationId,
                () -> transactions.required(() -> {
                    require(actor, AuthenticationAdministrativePermissions.SESSION_REVOKE,
                            new AdministrativeResource("authentication-session", id), now, correlationId);
                    ensureSession(actor, id, correlationId);
                    Registration registration = register(
                            actor, "api.authentication.session.revoke.v1", key, fingerprint, now);
                    if (registration.kind() == RegistrationKind.REPLAY) {
                        return replaySession(actor, registration, correlationId);
                    }
                    AuthenticationSession revoked = authentication.revokeSession(
                            actor.tenant(), id, revision, now);
                    complete(actor, "api.authentication.session.revoke.v1", key, fingerprint,
                            "authentication-session", revoked.id(), now);
                    return revoked;
                }), AuthenticationSession::id);
    }

    void requireRead(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        require(actor, permission, resource, now, correlationId);
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
            recordOutcome(actor, resourceType,
                    resourceId != null ? resourceId : resultId.apply(result), actionType,
                    AuditOutcome.SUCCESS, now, correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(actor, resourceType, resourceId, actionType,
                    auditOutcome(failure), now, correlationId);
            throw failure;
        }
    }

    private static AuditOutcome auditOutcome(RuntimeException failure) {
        return failure instanceof AuthenticationApiException api
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
            audit.append(actor.tenant(), new AuditRecordDraft(
                    ids.nextId(), occurredAt, actor.identityId(), actionType,
                    resourceType, resourceId, outcome, correlationId, null));
        } catch (RuntimeException auditFailure) {
            LOG.warn("Authentication AuditRecord append failed; correlationId={} actionType={} outcome={}",
                    correlationId, actionType, outcome);
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
        idempotency.complete(actor.tenant(), namespace, key, fingerprint, resourceType, resourceId, now);
    }

    private AuthenticationClient replayClient(
            AuthenticatedAdministrativeActor actor, Registration registration, UUID correlationId) {
        return replay(registration, "authentication-client", correlationId,
                () -> repository.findClient(actor.tenant(), registration.resourceId()).orElseThrow());
    }

    private AuthenticationLoginBinding replayLoginBinding(
            AuthenticatedAdministrativeActor actor, Registration registration, UUID correlationId) {
        return replay(registration, "authentication-login-binding", correlationId,
                () -> repository.findLoginBinding(actor.tenant(), registration.resourceId()).orElseThrow());
    }

    private AuthenticationSession replaySession(
            AuthenticatedAdministrativeActor actor, Registration registration, UUID correlationId) {
        return replay(registration, "authentication-session", correlationId,
                () -> repository.findSession(actor.tenant(), registration.resourceId()).orElseThrow());
    }

    private static <T> T replay(
            Registration registration,
            String resourceType,
            UUID correlationId,
            Supplier<T> loader) {
        if (!"COMPLETED".equals(registration.operationState())) {
            throw AuthenticationApiException.conflict(
                    correlationId, "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!resourceType.equals(registration.resourceType()) || registration.resourceId() == null) {
            throw new IllegalStateException("completed Authentication idempotency result is invalid");
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
            throw AuthenticationApiException.forbidden(correlationId);
        }
    }

    private void ensureClient(
            AuthenticatedAdministrativeActor actor, UUID id, UUID correlationId) {
        if (repository.findClient(actor.tenant(), id).isEmpty()) {
            throw AuthenticationApiException.notFound(correlationId);
        }
    }

    private void ensureLoginBinding(
            AuthenticatedAdministrativeActor actor, UUID id, UUID correlationId) {
        if (repository.findLoginBinding(actor.tenant(), id).isEmpty()) {
            throw AuthenticationApiException.notFound(correlationId);
        }
    }

    private void ensureSession(
            AuthenticatedAdministrativeActor actor, UUID id, UUID correlationId) {
        if (repository.findSession(actor.tenant(), id).isEmpty()) {
            throw AuthenticationApiException.notFound(correlationId);
        }
    }
}