package io.wyrmgate.iam.authentication.application;

import io.wyrmgate.iam.authentication.domain.AuthenticationClient;
import io.wyrmgate.iam.authentication.domain.AuthenticationLoginBinding;
import io.wyrmgate.iam.authentication.domain.AuthenticationSession;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.net.URI;

/** Authentication-owned authoritative persistence/query port. */
public interface AuthenticationRepository {

    void insertClient(TenantContext tenant, AuthenticationClient client);
    Optional<AuthenticationClient> findClient(TenantContext tenant, UUID clientId);
    Optional<AuthenticationClient> findClientByProtocolId(TenantContext tenant, String protocolClientId);
    List<AuthenticationClient> listClients(TenantContext tenant, Instant afterCreatedAt, UUID afterId, int limit);
    AuthenticationClient updateClient(
            TenantContext tenant,
            UUID clientId,
            String displayName,
            List<URI> redirectUris,
            List<URI> postLogoutRedirectUris,
            Set<String> scopes,
            long expectedRevision,
            Instant now);
    AuthenticationClient disableClient(
            TenantContext tenant, UUID clientId, long expectedRevision, Instant now);

    void insertLoginBinding(TenantContext tenant, AuthenticationLoginBinding binding);
    Optional<AuthenticationLoginBinding> findLoginBinding(TenantContext tenant, UUID bindingId);
    Optional<AuthenticationLoginBinding> findActiveLoginBindingByNormalizedIdentifier(
            TenantContext tenant, String normalizedLoginIdentifier);
    List<AuthenticationLoginBinding> listLoginBindings(
            TenantContext tenant, Instant afterCreatedAt, UUID afterId, int limit);
    AuthenticationLoginBinding updateLoginBindingDisplay(
            TenantContext tenant,
            UUID bindingId,
            String loginIdentifier,
            long expectedRevision,
            Instant now);
    AuthenticationLoginBinding disableLoginBinding(
            TenantContext tenant, UUID bindingId, long expectedRevision, Instant now);

    Optional<String> findSubjectForIdentity(TenantContext tenant, UUID identityId);
    void insertSubjectIdentifier(TenantContext tenant, UUID id, UUID identityId, String subjectValue, Instant now);

    void insertSession(TenantContext tenant, AuthenticationSession session, String sessionSecretDigest);
    Optional<AuthenticationSession> findSession(TenantContext tenant, UUID sessionId);
    List<AuthenticationSession> listSessions(TenantContext tenant, Instant afterCreatedAt, UUID afterId, int limit);
    AuthenticationSession revokeSession(
            TenantContext tenant, UUID sessionId, long expectedRevision, Instant now);
}