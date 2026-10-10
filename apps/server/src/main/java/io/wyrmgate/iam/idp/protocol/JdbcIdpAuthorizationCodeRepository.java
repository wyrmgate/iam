package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcIdpAuthorizationCodeRepository implements IdpAuthorizationCodeRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdpAuthorizationCodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantContext tenant, IdpAuthorizationCode code) {
        jdbc.update("""
                INSERT INTO platform.idp_authorization_code (
                    id, tenant_id, client_registration_id, client_registration_revision,
                    application_id, client_id, browser_session_id, principal_id, identity_id,
                    code_hash, redirect_uri, scopes, pkce_challenge,
                    nonce_value, auth_time, created_at, expires_at, consumed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                code.id(),
                tenant.tenantId(),
                code.clientRegistrationId(),
                code.clientRegistrationRevision(),
                code.applicationId(),
                code.clientId(),
                code.browserSessionId(),
                code.principalId(),
                code.identityId(),
                code.codeHash(),
                code.redirectUri(),
                serializeScopes(code.scopes()),
                code.pkceChallenge(),
                code.nonce(),
                Timestamp.from(code.authTime()),
                Timestamp.from(code.createdAt()),
                Timestamp.from(code.expiresAt()),
                code.consumedAt() == null ? null : Timestamp.from(code.consumedAt()));
    }

    @Override
    public Optional<IdpAuthorizationCode> consume(
            TenantContext tenant,
            String codeHash,
            Instant consumedAt) {
        return jdbc.query("""
                UPDATE platform.idp_authorization_code
                SET consumed_at = ?
                WHERE tenant_id = ?
                  AND code_hash = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                RETURNING id, client_registration_id, client_registration_revision,
                          application_id, client_id, browser_session_id, principal_id, identity_id,
                          code_hash, redirect_uri, scopes, pkce_challenge,
                          nonce_value, auth_time, created_at, expires_at, consumed_at
                """,
                (rs, rowNum) -> map(rs),
                Timestamp.from(consumedAt),
                tenant.tenantId(),
                codeHash,
                Timestamp.from(consumedAt))
                .stream()
                .findFirst();
    }

    private IdpAuthorizationCode map(ResultSet rs) throws SQLException {
        Timestamp consumedAt = rs.getTimestamp("consumed_at");
        return new IdpAuthorizationCode(
                rs.getObject("id", UUID.class),
                rs.getObject("client_registration_id", UUID.class),
                rs.getLong("client_registration_revision"),
                rs.getObject("application_id", UUID.class),
                rs.getString("client_id"),
                rs.getObject("browser_session_id", UUID.class),
                rs.getObject("principal_id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                rs.getString("code_hash"),
                rs.getString("redirect_uri"),
                parseScopes(rs.getString("scopes")),
                rs.getString("pkce_challenge"),
                rs.getString("nonce_value"),
                rs.getTimestamp("auth_time").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                consumedAt == null ? null : consumedAt.toInstant());
    }

    private static String serializeScopes(Set<SsoClientScope> scopes) {
        return scopes.stream()
                .map(SsoClientScope::protocolValue)
                .sorted()
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();
    }

    private static Set<SsoClientScope> parseScopes(String value) {
        LinkedHashSet<SsoClientScope> scopes = new LinkedHashSet<>();
        for (String item : value.split(" ")) {
            scopes.add(SsoClientScope.fromProtocolValue(item));
        }
        return scopes;
    }
}
