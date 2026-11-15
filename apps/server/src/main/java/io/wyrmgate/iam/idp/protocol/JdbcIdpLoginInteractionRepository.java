package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcIdpLoginInteractionRepository implements IdpLoginInteractionRepository {
    private final JdbcTemplate jdbc;
    JdbcIdpLoginInteractionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void insert(Interaction i) {
        var r = i.request();
        jdbc.update("""
                INSERT INTO platform.idp_login_interaction (interaction_hash, browser_hash, tenant_id,
                    client_registration_id, client_revision, authentication_target_id, client_id,
                    redirect_uri, scopes, state_value, pkce_challenge, nonce_value, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, i.formHash(), i.browserHash(), i.tenant().tenantId(), i.registrationId(),
                i.clientRevision(), i.targetId(), r.clientId(), r.redirectUri(), r.scope(), r.state(),
                r.codeChallenge(), r.nonce(), Timestamp.from(i.expiresAt()));
    }

    @Override public Optional<Interaction> consume(String formHash, String browserHash, Instant now) {
        return jdbc.query("""
                DELETE FROM platform.idp_login_interaction
                WHERE interaction_hash = ? AND browser_hash = ? AND expires_at > ?
                RETURNING *
                """, (rs, row) -> new Interaction(rs.getString("interaction_hash"), rs.getString("browser_hash"),
                new TenantContext(rs.getObject("tenant_id", UUID.class)), rs.getObject("client_registration_id", UUID.class),
                rs.getLong("client_revision"), rs.getObject("authentication_target_id", UUID.class),
                new IdpProtocolService.AuthorizationRequest("code", rs.getString("client_id"), rs.getString("redirect_uri"),
                    rs.getString("scopes"), rs.getString("state_value"), rs.getString("pkce_challenge"), "S256", rs.getString("nonce_value")),
                rs.getTimestamp("expires_at").toInstant()), formHash, browserHash, Timestamp.from(now)).stream().findFirst();
    }
}
