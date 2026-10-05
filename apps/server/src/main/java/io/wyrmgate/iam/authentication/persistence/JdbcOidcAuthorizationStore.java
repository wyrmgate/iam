package io.wyrmgate.iam.authentication.persistence;

import io.wyrmgate.iam.authentication.application.OidcAuthorizationStore;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for hashed, single-use OIDC authorization request and code state. */
public final class JdbcOidcAuthorizationStore implements OidcAuthorizationStore {

    private final JdbcTemplate jdbc;

    public JdbcOidcAuthorizationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createRequest(PendingRequest request) {
        jdbc.update("""
                INSERT INTO authentication.authorization_request (
                    id, tenant_id, client_id, request_secret_digest, redirect_uri,
                    requested_scopes, client_state, nonce_value, pkce_challenge, pkce_method,
                    expires_at, consumed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'S256', ?, NULL, ?)
                """,
                request.id(), request.tenant().tenantId(), request.clientResourceId(),
                request.requestSecretDigest(), request.redirectUri(), scopes(request.scopes()),
                request.clientState(), request.nonce(), request.pkceChallenge(),
                Timestamp.from(request.expiresAt()), Timestamp.from(request.createdAt()));
    }

    @Override
    public PendingRequest consumeRequest(String requestSecretDigest, Instant now) {
        return jdbc.query("""
                UPDATE authentication.authorization_request
                SET consumed_at = ?
                WHERE request_secret_digest = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                RETURNING id, tenant_id, client_id, request_secret_digest, redirect_uri,
                          requested_scopes, client_state, nonce_value, pkce_challenge,
                          expires_at, created_at
                """, (rs, row) -> request(rs),
                Timestamp.from(now), requestSecretDigest, Timestamp.from(now))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("invalid or expired authorization request"));
    }

    @Override
    public void createCode(AuthorizationCode code) {
        jdbc.update("""
                INSERT INTO authentication.authorization_code (
                    id, tenant_id, client_id, session_id, code_secret_digest, redirect_uri,
                    authorized_scopes, nonce_value, pkce_challenge, pkce_method,
                    expires_at, consumed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'S256', ?, NULL, ?)
                """,
                code.id(), code.tenant().tenantId(), code.clientResourceId(), code.sessionId(),
                code.codeSecretDigest(), code.redirectUri(), scopes(code.scopes()), code.nonce(),
                code.pkceChallenge(), Timestamp.from(code.expiresAt()), Timestamp.from(code.createdAt()));
    }

    @Override
    public AuthorizationCode consumeCode(String codeSecretDigest, Instant now) {
        return jdbc.query("""
                UPDATE authentication.authorization_code
                SET consumed_at = ?
                WHERE code_secret_digest = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                RETURNING id, tenant_id, client_id, session_id, code_secret_digest, redirect_uri,
                          authorized_scopes, nonce_value, pkce_challenge, expires_at, created_at
                """, (rs, row) -> code(rs),
                Timestamp.from(now), codeSecretDigest, Timestamp.from(now))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("invalid or expired authorization code"));
    }

    private static PendingRequest request(ResultSet rs) throws SQLException {
        return new PendingRequest(
                rs.getObject("id", UUID.class),
                new TenantContext(rs.getObject("tenant_id", UUID.class)),
                rs.getObject("client_id", UUID.class),
                rs.getString("request_secret_digest"),
                rs.getString("redirect_uri"),
                scopes(rs.getString("requested_scopes")),
                rs.getString("client_state"),
                rs.getString("nonce_value"),
                rs.getString("pkce_challenge"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private static AuthorizationCode code(ResultSet rs) throws SQLException {
        return new AuthorizationCode(
                rs.getObject("id", UUID.class),
                new TenantContext(rs.getObject("tenant_id", UUID.class)),
                rs.getObject("client_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("code_secret_digest"),
                rs.getString("redirect_uri"),
                scopes(rs.getString("authorized_scopes")),
                rs.getString("nonce_value"),
                rs.getString("pkce_challenge"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String scopes(Set<String> scopes) {
        return scopes.stream().sorted().reduce((left, right) -> left + " " + right).orElse("");
    }

    private static Set<String> scopes(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Set.copyOf(new LinkedHashSet<>(Arrays.asList(value.split(" "))));
    }
}