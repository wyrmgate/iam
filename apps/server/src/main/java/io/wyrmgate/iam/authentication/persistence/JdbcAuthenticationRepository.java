package io.wyrmgate.iam.authentication.persistence;

import io.wyrmgate.iam.authentication.application.AuthenticationRepository;
import io.wyrmgate.iam.authentication.domain.AuthenticationAssurance;
import io.wyrmgate.iam.authentication.domain.AuthenticationClient;
import io.wyrmgate.iam.authentication.domain.AuthenticationLoginBinding;
import io.wyrmgate.iam.authentication.domain.AuthenticationSession;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for Authentication-owned IdP/SSO authoritative state. */
public final class JdbcAuthenticationRepository implements AuthenticationRepository {

    private final JdbcTemplate jdbc;

    public JdbcAuthenticationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertClient(TenantContext tenant, AuthenticationClient client) {
        jdbc.update("""
                INSERT INTO authentication.client (
                    id, tenant_id, protocol_client_id, display_name, client_type,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                client.id(), tenant.tenantId(), client.clientId(), client.displayName(),
                client.clientType().name(), client.lifecycleState().name(), client.revision(),
                Timestamp.from(client.createdAt()), Timestamp.from(client.updatedAt()));
        replaceClientChildren(tenant, client);
    }

    @Override
    public Optional<AuthenticationClient> findClient(TenantContext tenant, UUID clientId) {
        List<ClientRow> rows = jdbc.query("""
                SELECT id, protocol_client_id, display_name, client_type, lifecycle_state,
                       revision, created_at, updated_at
                FROM authentication.client
                WHERE tenant_id = ? AND id = ?
                """, (rs, n) -> clientRow(rs), tenant.tenantId(), clientId);
        return rows.stream().findFirst().map(row -> materializeClient(tenant, row));
    }

    @Override
    public Optional<AuthenticationClient> findClientByProtocolId(
            TenantContext tenant, String protocolClientId) {
        List<ClientRow> rows = jdbc.query("""
                SELECT id, protocol_client_id, display_name, client_type, lifecycle_state,
                       revision, created_at, updated_at
                FROM authentication.client
                WHERE tenant_id = ? AND protocol_client_id = ?
                """, (rs, n) -> clientRow(rs), tenant.tenantId(), protocolClientId);
        return rows.stream().findFirst().map(row -> materializeClient(tenant, row));
    }

    @Override
    public List<AuthenticationClient> listClients(
            TenantContext tenant, Instant afterCreatedAt, UUID afterId, int limit) {
        String sql = afterCreatedAt == null
                ? """
                  SELECT id, protocol_client_id, display_name, client_type, lifecycle_state,
                         revision, created_at, updated_at
                  FROM authentication.client
                  WHERE tenant_id = ?
                  ORDER BY created_at, id
                  LIMIT ?
                  """
                : """
                  SELECT id, protocol_client_id, display_name, client_type, lifecycle_state,
                         revision, created_at, updated_at
                  FROM authentication.client
                  WHERE tenant_id = ?
                    AND (created_at, id) > (?, ?)
                  ORDER BY created_at, id
                  LIMIT ?
                  """;
        List<ClientRow> rows = afterCreatedAt == null
                ? jdbc.query(sql, (rs, n) -> clientRow(rs), tenant.tenantId(), limit)
                : jdbc.query(sql, (rs, n) -> clientRow(rs), tenant.tenantId(),
                        Timestamp.from(afterCreatedAt), afterId, limit);
        return rows.stream().map(row -> materializeClient(tenant, row)).toList();
    }

    @Override
    public AuthenticationClient updateClient(
            TenantContext tenant,
            UUID clientId,
            String displayName,
            List<URI> redirectUris,
            List<URI> postLogoutRedirectUris,
            Set<String> scopes,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE authentication.client
                SET display_name = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                  AND lifecycle_state = 'ACTIVE'
                """, displayName, Timestamp.from(now), tenant.tenantId(), clientId, expectedRevision);
        requireUpdated(affected, "authentication-client", clientId, expectedRevision);
        jdbc.update("DELETE FROM authentication.client_redirect_uri WHERE tenant_id = ? AND client_id = ?",
                tenant.tenantId(), clientId);
        jdbc.update("DELETE FROM authentication.client_post_logout_redirect_uri WHERE tenant_id = ? AND client_id = ?",
                tenant.tenantId(), clientId);
        jdbc.update("DELETE FROM authentication.client_scope WHERE tenant_id = ? AND client_id = ?",
                tenant.tenantId(), clientId);
        insertUris("authentication.client_redirect_uri", tenant, clientId, redirectUris);
        insertUris("authentication.client_post_logout_redirect_uri", tenant, clientId, postLogoutRedirectUris);
        insertScopes(tenant, clientId, scopes);
        return findClient(tenant, clientId).orElseThrow();
    }

    @Override
    public AuthenticationClient disableClient(
            TenantContext tenant, UUID clientId, long expectedRevision, Instant now) {
        int affected = jdbc.update("""
                UPDATE authentication.client
                SET lifecycle_state = 'DISABLED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """, Timestamp.from(now), tenant.tenantId(), clientId, expectedRevision);
        requireUpdated(affected, "authentication-client", clientId, expectedRevision);
        return findClient(tenant, clientId).orElseThrow();
    }

    @Override
    public void insertLoginBinding(TenantContext tenant, AuthenticationLoginBinding binding) {
        jdbc.update("""
                INSERT INTO authentication.login_binding (
                    id, tenant_id, principal_id, identity_id, login_identifier,
                    normalized_login_identifier, lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                binding.id(), tenant.tenantId(), binding.principalId(), binding.identityId(),
                binding.loginIdentifier(), binding.normalizedLoginIdentifier(), binding.lifecycleState().name(),
                binding.revision(), Timestamp.from(binding.createdAt()), Timestamp.from(binding.updatedAt()));
    }

    @Override
    public Optional<AuthenticationLoginBinding> findLoginBinding(
            TenantContext tenant, UUID bindingId) {
        return jdbc.query("""
                SELECT id, principal_id, identity_id, login_identifier, normalized_login_identifier,
                       lifecycle_state, revision, created_at, updated_at
                FROM authentication.login_binding
                WHERE tenant_id = ? AND id = ?
                """, (rs, n) -> loginBinding(rs), tenant.tenantId(), bindingId)
                .stream().findFirst();
    }

    @Override
    public Optional<AuthenticationLoginBinding> findActiveLoginBindingByNormalizedIdentifier(
            TenantContext tenant, String normalizedLoginIdentifier) {
        return jdbc.query("""
                SELECT id, principal_id, identity_id, login_identifier, normalized_login_identifier,
                       lifecycle_state, revision, created_at, updated_at
                FROM authentication.login_binding
                WHERE tenant_id = ? AND normalized_login_identifier = ? AND lifecycle_state = 'ACTIVE'
                """, (rs, n) -> loginBinding(rs), tenant.tenantId(), normalizedLoginIdentifier)
                .stream().findFirst();
    }

    @Override
    public List<AuthenticationLoginBinding> listLoginBindings(
            TenantContext tenant, Instant afterCreatedAt, UUID afterId, int limit) {
        if (afterCreatedAt == null) {
            return jdbc.query("""
                    SELECT id, principal_id, identity_id, login_identifier, normalized_login_identifier,
                           lifecycle_state, revision, created_at, updated_at
                    FROM authentication.login_binding
                    WHERE tenant_id = ?
                    ORDER BY created_at, id
                    LIMIT ?
                    """, (rs, n) -> loginBinding(rs), tenant.tenantId(), limit);
        }
        return jdbc.query("""
                SELECT id, principal_id, identity_id, login_identifier, normalized_login_identifier,
                       lifecycle_state, revision, created_at, updated_at
                FROM authentication.login_binding
                WHERE tenant_id = ? AND (created_at, id) > (?, ?)
                ORDER BY created_at, id
                LIMIT ?
                """, (rs, n) -> loginBinding(rs), tenant.tenantId(), Timestamp.from(afterCreatedAt), afterId, limit);
    }

    @Override
    public AuthenticationLoginBinding updateLoginBindingDisplay(
            TenantContext tenant,
            UUID bindingId,
            String loginIdentifier,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE authentication.login_binding
                SET login_identifier = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """, loginIdentifier, Timestamp.from(now), tenant.tenantId(), bindingId, expectedRevision);
        requireUpdated(affected, "authentication-login-binding", bindingId, expectedRevision);
        return findLoginBinding(tenant, bindingId).orElseThrow();
    }

    @Override
    public AuthenticationLoginBinding disableLoginBinding(
            TenantContext tenant, UUID bindingId, long expectedRevision, Instant now) {
        int affected = jdbc.update("""
                UPDATE authentication.login_binding
                SET lifecycle_state = 'DISABLED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ?
                """, Timestamp.from(now), tenant.tenantId(), bindingId, expectedRevision);
        requireUpdated(affected, "authentication-login-binding", bindingId, expectedRevision);
        return findLoginBinding(tenant, bindingId).orElseThrow();
    }

    @Override
    public Optional<String> findSubjectForIdentity(TenantContext tenant, UUID identityId) {
        return jdbc.query("""
                SELECT subject_value
                FROM authentication.subject_identifier
                WHERE tenant_id = ? AND identity_id = ?
                """, (rs, n) -> rs.getString("subject_value"), tenant.tenantId(), identityId)
                .stream().findFirst();
    }

    @Override
    public void insertSubjectIdentifier(
            TenantContext tenant, UUID id, UUID identityId, String subjectValue, Instant now) {
        jdbc.update("""
                INSERT INTO authentication.subject_identifier (
                    id, tenant_id, identity_id, subject_value, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, tenant.tenantId(), identityId, subjectValue, Timestamp.from(now));
    }

    @Override
    public void insertSession(
            TenantContext tenant, AuthenticationSession session, String sessionSecretDigest) {
        jdbc.update("""
                INSERT INTO authentication.session (
                    id, tenant_id, identity_id, principal_id, session_secret_digest,
                    assurance_level, lifecycle_state, authenticated_at, last_seen_at,
                    expires_at, revoked_at, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                session.id(), tenant.tenantId(), session.identityId(), session.principalId(), sessionSecretDigest,
                session.assurance().name(), session.lifecycleState().name(),
                Timestamp.from(session.authenticatedAt()), Timestamp.from(session.lastSeenAt()),
                Timestamp.from(session.expiresAt()), timestamp(session.revokedAt()), session.revision(),
                Timestamp.from(session.createdAt()), Timestamp.from(session.updatedAt()));
    }

    @Override
    public Optional<AuthenticationSession> findSession(TenantContext tenant, UUID sessionId) {
        return jdbc.query("""
                SELECT id, identity_id, principal_id, assurance_level, lifecycle_state,
                       authenticated_at, last_seen_at, expires_at, revoked_at,
                       revision, created_at, updated_at
                FROM authentication.session
                WHERE tenant_id = ? AND id = ?
                """, (rs, n) -> session(rs), tenant.tenantId(), sessionId)
                .stream().findFirst();
    }

    @Override
    public List<AuthenticationSession> listSessions(
            TenantContext tenant, Instant afterCreatedAt, UUID afterId, int limit) {
        if (afterCreatedAt == null) {
            return jdbc.query("""
                    SELECT id, identity_id, principal_id, assurance_level, lifecycle_state,
                           authenticated_at, last_seen_at, expires_at, revoked_at,
                           revision, created_at, updated_at
                    FROM authentication.session
                    WHERE tenant_id = ?
                    ORDER BY created_at, id
                    LIMIT ?
                    """, (rs, n) -> session(rs), tenant.tenantId(), limit);
        }
        return jdbc.query("""
                SELECT id, identity_id, principal_id, assurance_level, lifecycle_state,
                       authenticated_at, last_seen_at, expires_at, revoked_at,
                       revision, created_at, updated_at
                FROM authentication.session
                WHERE tenant_id = ? AND (created_at, id) > (?, ?)
                ORDER BY created_at, id
                LIMIT ?
                """, (rs, n) -> session(rs), tenant.tenantId(), Timestamp.from(afterCreatedAt), afterId, limit);
    }

    @Override
    public AuthenticationSession revokeSession(
            TenantContext tenant, UUID sessionId, long expectedRevision, Instant now) {
        int affected = jdbc.update("""
                UPDATE authentication.session
                SET lifecycle_state = 'REVOKED', revoked_at = ?, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND revision = ? AND lifecycle_state = 'ACTIVE'
                """, Timestamp.from(now), Timestamp.from(now), tenant.tenantId(), sessionId, expectedRevision);
        requireUpdated(affected, "authentication-session", sessionId, expectedRevision);
        return findSession(tenant, sessionId).orElseThrow();
    }

    private void replaceClientChildren(TenantContext tenant, AuthenticationClient client) {
        insertUris("authentication.client_redirect_uri", tenant, client.id(), client.redirectUris());
        insertUris("authentication.client_post_logout_redirect_uri", tenant, client.id(), client.postLogoutRedirectUris());
        insertScopes(tenant, client.id(), client.scopes());
    }

    private void insertUris(String table, TenantContext tenant, UUID clientId, List<URI> uris) {
        for (int i = 0; i < uris.size(); i++) {
            jdbc.update("INSERT INTO " + table + " (tenant_id, client_id, redirect_uri, ordinal) VALUES (?, ?, ?, ?)",
                    tenant.tenantId(), clientId, uris.get(i).toString(), i);
        }
    }

    private void insertScopes(TenantContext tenant, UUID clientId, Set<String> scopes) {
        int ordinal = 0;
        for (String scope : scopes) {
            jdbc.update("""
                    INSERT INTO authentication.client_scope (tenant_id, client_id, scope_name, ordinal)
                    VALUES (?, ?, ?, ?)
                    """, tenant.tenantId(), clientId, scope, ordinal++);
        }
    }

    private AuthenticationClient materializeClient(TenantContext tenant, ClientRow row) {
        List<URI> redirects = jdbc.query("""
                SELECT redirect_uri FROM authentication.client_redirect_uri
                WHERE tenant_id = ? AND client_id = ? ORDER BY ordinal
                """, (rs, n) -> URI.create(rs.getString("redirect_uri")), tenant.tenantId(), row.id());
        List<URI> logoutRedirects = jdbc.query("""
                SELECT redirect_uri FROM authentication.client_post_logout_redirect_uri
                WHERE tenant_id = ? AND client_id = ? ORDER BY ordinal
                """, (rs, n) -> URI.create(rs.getString("redirect_uri")), tenant.tenantId(), row.id());
        LinkedHashSet<String> scopes = new LinkedHashSet<>(jdbc.query("""
                SELECT scope_name FROM authentication.client_scope
                WHERE tenant_id = ? AND client_id = ? ORDER BY ordinal
                """, (rs, n) -> rs.getString("scope_name"), tenant.tenantId(), row.id()));
        return new AuthenticationClient(
                row.id(), row.protocolClientId(), row.displayName(), row.clientType(),
                redirects, logoutRedirects, scopes, row.lifecycleState(), row.revision(),
                row.createdAt(), row.updatedAt());
    }

    private static ClientRow clientRow(ResultSet rs) throws SQLException {
        return new ClientRow(
                rs.getObject("id", UUID.class),
                rs.getString("protocol_client_id"),
                rs.getString("display_name"),
                AuthenticationClient.ClientType.valueOf(rs.getString("client_type")),
                AuthenticationClient.LifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static AuthenticationLoginBinding loginBinding(ResultSet rs) throws SQLException {
        return new AuthenticationLoginBinding(
                rs.getObject("id", UUID.class),
                rs.getObject("principal_id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                rs.getString("login_identifier"),
                rs.getString("normalized_login_identifier"),
                AuthenticationLoginBinding.LifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static AuthenticationSession session(ResultSet rs) throws SQLException {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new AuthenticationSession(
                rs.getObject("id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                rs.getObject("principal_id", UUID.class),
                AuthenticationAssurance.valueOf(rs.getString("assurance_level")),
                AuthenticationSession.LifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getTimestamp("authenticated_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                revoked == null ? null : revoked.toInstant(),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static void requireUpdated(int affected, String resource, UUID id, long expectedRevision) {
        if (affected != 1) throw new StaleWriteException(resource, id, expectedRevision);
    }

    private record ClientRow(
            UUID id,
            String protocolClientId,
            String displayName,
            AuthenticationClient.ClientType clientType,
            AuthenticationClient.LifecycleState lifecycleState,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}
}