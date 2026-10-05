package io.wyrmgate.iam.authentication.application;

import io.wyrmgate.iam.authentication.domain.AuthenticationAssurance;
import io.wyrmgate.iam.authentication.domain.AuthenticationClient;
import io.wyrmgate.iam.authentication.domain.AuthenticationLoginBinding;
import io.wyrmgate.iam.authentication.domain.AuthenticationSession;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Authentication-owned commands and reads for client, login-binding, subject and session state. */
public final class AuthenticationService {

    private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthenticationRepository repository;
    private final AuthenticationSubjectQuery subjects;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public AuthenticationService(
            AuthenticationRepository repository,
            AuthenticationSubjectQuery subjects,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.subjects = Objects.requireNonNull(subjects, "subjects");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public AuthenticationClient createClient(
            TenantContext tenant,
            String displayName,
            AuthenticationClient.ClientType clientType,
            List<URI> redirectUris,
            List<URI> postLogoutRedirectUris,
            Set<String> scopes,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        return transactions.required(() -> {
            UUID id = ids.nextId();
            String protocolClientId = "wg_" + id.toString().replace("-", "");
            AuthenticationClient client = new AuthenticationClient(
                    id, protocolClientId, displayName, clientType, redirectUris,
                    postLogoutRedirectUris, scopes, AuthenticationClient.LifecycleState.ACTIVE,
                    1, now, now);
            repository.insertClient(tenant, client);
            return client;
        });
    }

    public AuthenticationClient updateClient(
            TenantContext tenant, UUID clientId, String displayName,
            List<URI> redirectUris, List<URI> postLogoutRedirectUris,
            Set<String> scopes, long expectedRevision, Instant now) {
        AuthenticationClient current = repository.findClient(tenant, clientId)
                .orElseThrow(() -> new IllegalArgumentException("authentication client does not exist"));
        new AuthenticationClient(
                current.id(), current.clientId(), displayName, current.clientType(), redirectUris,
                postLogoutRedirectUris, scopes, current.lifecycleState(), current.revision(),
                current.createdAt(), now);
        return transactions.required(() -> repository.updateClient(
                tenant, clientId, displayName, redirectUris, postLogoutRedirectUris,
                scopes, expectedRevision, now));
    }

    public AuthenticationClient disableClient(
            TenantContext tenant, UUID clientId, long expectedRevision, Instant now) {
        return transactions.required(() -> repository.disableClient(
                tenant, clientId, expectedRevision, now));
    }

    public AuthenticationClient getClient(TenantContext tenant, UUID clientId) {
        return repository.findClient(tenant, clientId)
                .orElseThrow(() -> new IllegalArgumentException("authentication client does not exist"));
    }

    public AuthenticationClient getActiveClientByProtocolId(TenantContext tenant, String protocolClientId) {
        AuthenticationClient client = repository.findClientByProtocolId(tenant, protocolClientId)
                .orElseThrow(() -> new IllegalArgumentException("authentication client does not exist"));
        if (client.lifecycleState() != AuthenticationClient.LifecycleState.ACTIVE) {
            throw new IllegalStateException("authentication client is disabled");
        }
        return client;
    }

    public List<AuthenticationClient> listClients(
            TenantContext tenant, Instant afterCreatedAt, UUID afterId, int limit) {
        return repository.listClients(tenant, afterCreatedAt, afterId, pageSize(limit));
    }

    public AuthenticationLoginBinding createLoginBinding(
            TenantContext tenant, UUID principalId, String loginIdentifier, Instant now) {
        AuthenticationSubjectQuery.Result subject = subjects.resolve(tenant, principalId);
        if (subject.status() == AuthenticationSubjectQuery.Status.UNAVAILABLE) {
            throw new IllegalStateException("governed authentication subject evaluation unavailable");
        }
        if (subject.status() != AuthenticationSubjectQuery.Status.ELIGIBLE) {
            throw new IllegalArgumentException("principal is not eligible for authentication");
        }
        String normalized = AuthenticationLoginBinding.normalize(loginIdentifier);
        return transactions.required(() -> {
            if (repository.findActiveLoginBindingByNormalizedIdentifier(tenant, normalized).isPresent()) {
                throw new IllegalArgumentException("login identifier is already bound");
            }
            AuthenticationLoginBinding binding = new AuthenticationLoginBinding(
                    ids.nextId(), principalId, subject.identityId(), loginIdentifier, normalized,
                    AuthenticationLoginBinding.LifecycleState.ACTIVE, 1, now, now);
            repository.insertLoginBinding(tenant, binding);
            return binding;
        });
    }

    public AuthenticationLoginBinding updateLoginBindingDisplay(
            TenantContext tenant, UUID bindingId, String loginIdentifier,
            long expectedRevision, Instant now) {
        AuthenticationLoginBinding current = getLoginBinding(tenant, bindingId);
        if (!AuthenticationLoginBinding.normalize(loginIdentifier)
                .equals(current.normalizedLoginIdentifier())) {
            throw new IllegalArgumentException(
                    "login identifier normalized value is immutable; create a replacement binding");
        }
        return transactions.required(() -> repository.updateLoginBindingDisplay(
                tenant, bindingId, loginIdentifier, expectedRevision, now));
    }

    public AuthenticationLoginBinding disableLoginBinding(
            TenantContext tenant, UUID bindingId, long expectedRevision, Instant now) {
        return transactions.required(() -> repository.disableLoginBinding(
                tenant, bindingId, expectedRevision, now));
    }

    public AuthenticationLoginBinding getLoginBinding(TenantContext tenant, UUID bindingId) {
        return repository.findLoginBinding(tenant, bindingId)
                .orElseThrow(() -> new IllegalArgumentException("authentication login binding does not exist"));
    }

    public List<AuthenticationLoginBinding> listLoginBindings(
            TenantContext tenant, Instant afterCreatedAt, UUID afterId, int limit) {
        return repository.listLoginBindings(tenant, afterCreatedAt, afterId, pageSize(limit));
    }

    public AuthenticationLoginBinding resolveActiveLogin(TenantContext tenant, String loginIdentifier) {
        String normalized = AuthenticationLoginBinding.normalize(loginIdentifier);
        AuthenticationLoginBinding binding = repository
                .findActiveLoginBindingByNormalizedIdentifier(tenant, normalized)
                .orElseThrow(() -> new IllegalArgumentException("authentication failed"));
        AuthenticationSubjectQuery.Result subject = subjects.resolve(tenant, binding.principalId());
        if (subject.status() != AuthenticationSubjectQuery.Status.ELIGIBLE
                || !binding.identityId().equals(subject.identityId())) {
            throw new IllegalArgumentException("authentication failed");
        }
        return binding;
    }

    public String subjectForIdentity(TenantContext tenant, UUID identityId, Instant now) {
        return transactions.required(() -> repository.findSubjectForIdentity(tenant, identityId)
                .orElseGet(() -> {
                    String value = randomOpaque(32);
                    repository.insertSubjectIdentifier(tenant, ids.nextId(), identityId, value, now);
                    return value;
                }));
    }

    /** Persists session metadata using only a digest; raw browser session authority is never domain state. */
    public AuthenticationSession createSession(
            TenantContext tenant, UUID principalId, AuthenticationAssurance assurance,
            String sessionSecretDigest, Instant now) {
        AuthenticationSubjectQuery.Result subject = subjects.resolve(tenant, principalId);
        if (subject.status() == AuthenticationSubjectQuery.Status.UNAVAILABLE) {
            throw new IllegalStateException("governed authentication subject evaluation unavailable");
        }
        if (subject.status() != AuthenticationSubjectQuery.Status.ELIGIBLE) {
            throw new IllegalArgumentException("principal is not eligible for authentication");
        }
        if (sessionSecretDigest == null || sessionSecretDigest.isBlank()) {
            throw new IllegalArgumentException("sessionSecretDigest must not be blank");
        }
        return transactions.required(() -> {
            AuthenticationSession session = new AuthenticationSession(
                    ids.nextId(), subject.identityId(), principalId, assurance,
                    AuthenticationSession.LifecycleState.ACTIVE,
                    now, now, now.plus(DEFAULT_SESSION_TTL), null, 1, now, now);
            repository.insertSession(tenant, session, sessionSecretDigest);
            return session;
        });
    }

    public AuthenticationSession revokeSession(
            TenantContext tenant, UUID sessionId, long expectedRevision, Instant now) {
        return transactions.required(() -> repository.revokeSession(
                tenant, sessionId, expectedRevision, now));
    }

    public AuthenticationSession getSession(TenantContext tenant, UUID sessionId) {
        return repository.findSession(tenant, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("authentication session does not exist"));
    }

    public List<AuthenticationSession> listSessions(
            TenantContext tenant, Instant afterCreatedAt, UUID afterId, int limit) {
        return repository.listSessions(tenant, afterCreatedAt, afterId, pageSize(limit));
    }

    private static int pageSize(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        return limit;
    }

    private static String randomOpaque(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}