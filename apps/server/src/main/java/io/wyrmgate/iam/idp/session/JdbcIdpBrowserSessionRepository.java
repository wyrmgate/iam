package io.wyrmgate.iam.idp.session;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.idp.federation.FederatedAuthenticationAnchor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcIdpBrowserSessionRepository
        implements IdpBrowserSessionRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdpBrowserSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantContext tenant, IdpBrowserSession session) {
        var anchor=session.federatedAnchor();
        if(anchor!=null&&!tenant.tenantId().equals(anchor.tenantId()))throw new IllegalArgumentException("foreign session anchor");
        jdbc.update("""
                INSERT INTO platform.idp_browser_session (
                    id, tenant_id, principal_id, identity_id,
                    credential_id, credential_revision, tenant_revision, principal_revision, identity_revision, token_hash,
                    created_at, last_seen_at, idle_expires_at,
                    absolute_expires_at, revoked_at, federation_trust_id,federation_trust_revision,
                    federation_link_id,federation_link_revision,federation_authenticated_at,federation_proof_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?,?,?,?,?,?)
                """,
                session.id(),
                tenant.tenantId(),
                session.principalId(),
                session.identityId(),
                session.credentialId(),
                anchor==null?session.credentialRevision():null,
                session.tenantRevision(), session.principalRevision(), session.identityRevision(),
                session.tokenHash(),
                Timestamp.from(session.createdAt()),
                Timestamp.from(session.lastSeenAt()),
                Timestamp.from(session.idleExpiresAt()),
                Timestamp.from(session.absoluteExpiresAt()),
                timestamp(session.revokedAt()),anchor==null?null:anchor.trustId(),anchor==null?null:anchor.trustRevision(),
                anchor==null?null:anchor.linkId(),anchor==null?null:anchor.linkRevision(),
                anchor==null?null:Timestamp.from(anchor.authenticatedAt()),anchor==null?null:Timestamp.from(anchor.expiresAt()));
    }

    @Override
    public Optional<IdpBrowserSession> findByTokenHash(
            TenantContext tenant,
            String tokenHash) {
        return jdbc.query("""
                SELECT tenant_id,id, principal_id, identity_id,
                       credential_id, credential_revision, tenant_revision, principal_revision, identity_revision, token_hash,
                       created_at, last_seen_at, idle_expires_at,
                       absolute_expires_at, revoked_at, federation_trust_id,federation_trust_revision,
                       federation_link_id,federation_link_revision,federation_authenticated_at,federation_proof_expires_at
                FROM platform.idp_browser_session
                WHERE tenant_id = ? AND token_hash = ?
                """,
                (rs,row) -> session(rs),
                tenant.tenantId(),
                tokenHash)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<LocatedSession> findByTokenHash(String tokenHash) {
        return jdbc.query("""
                SELECT tenant_id, id, principal_id, identity_id,
                       credential_id, credential_revision, tenant_revision, principal_revision, identity_revision, token_hash,
                       created_at, last_seen_at, idle_expires_at,
                       absolute_expires_at, revoked_at, federation_trust_id,federation_trust_revision,
                       federation_link_id,federation_link_revision,federation_authenticated_at,federation_proof_expires_at
                FROM platform.idp_browser_session
                WHERE token_hash = ?
                """,
                (rs, row) -> new LocatedSession(
                        new TenantContext(rs.getObject("tenant_id", UUID.class)),
                        session(rs)),
                tokenHash)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<IdpBrowserSession> findById(TenantContext tenant, UUID sessionId) {
        return jdbc.query("""
                SELECT tenant_id,id, principal_id, identity_id,
                       credential_id, credential_revision, tenant_revision, principal_revision, identity_revision, token_hash,
                       created_at, last_seen_at, idle_expires_at,
                       absolute_expires_at, revoked_at, federation_trust_id,federation_trust_revision,
                       federation_link_id,federation_link_revision,federation_authenticated_at,federation_proof_expires_at
                FROM platform.idp_browser_session
                WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> session(rs), tenant.tenantId(), sessionId)
                .stream().findFirst();
    }

    @Override
    public boolean touch(
            TenantContext tenant,
            UUID sessionId,
            Instant lastSeenAt,
            Instant idleExpiresAt) {
        return jdbc.update("""
                UPDATE platform.idp_browser_session
                SET last_seen_at = GREATEST(last_seen_at, ?),
                    idle_expires_at = LEAST(absolute_expires_at, GREATEST(idle_expires_at, ?))
                WHERE tenant_id = ?
                  AND id = ?
                  AND revoked_at IS NULL
                  AND absolute_expires_at > ?
                  AND idle_expires_at > ?
                  AND created_at <= ?
                """,
                Timestamp.from(lastSeenAt),
                Timestamp.from(idleExpiresAt),
                tenant.tenantId(),
                sessionId,
                Timestamp.from(lastSeenAt), Timestamp.from(lastSeenAt), Timestamp.from(lastSeenAt)) == 1;
    }

    @Override
    public boolean revoke(
            TenantContext tenant,
            UUID sessionId,
            Instant revokedAt) {
        return jdbc.update("""
                UPDATE platform.idp_browser_session
                SET revoked_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND revoked_at IS NULL
                """,
                Timestamp.from(revokedAt),
                tenant.tenantId(),
                sessionId) == 1;
    }

    private static IdpBrowserSession session(ResultSet rs) throws SQLException {
        UUID trust=rs.getObject("federation_trust_id",UUID.class);
        FederatedAuthenticationAnchor anchor=trust==null?null:new FederatedAuthenticationAnchor(
                rs.getObject("tenant_id",UUID.class),rs.getLong("tenant_revision"),trust,rs.getLong("federation_trust_revision"),
                rs.getObject("federation_link_id",UUID.class),rs.getLong("federation_link_revision"),
                rs.getObject("principal_id",UUID.class),rs.getLong("principal_revision"),rs.getObject("identity_id",UUID.class),
                rs.getLong("identity_revision"),rs.getTimestamp("federation_authenticated_at").toInstant(),
                rs.getTimestamp("federation_proof_expires_at").toInstant());
        return new IdpBrowserSession(
                rs.getObject("id", UUID.class),
                rs.getObject("principal_id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                rs.getObject("credential_id", UUID.class),
                rs.getLong("credential_revision"),
                rs.getObject("tenant_revision", Long.class),
                rs.getObject("principal_revision", Long.class),
                rs.getObject("identity_revision", Long.class),
                rs.getString("token_hash"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getTimestamp("idle_expires_at").toInstant(),
                rs.getTimestamp("absolute_expires_at").toInstant(),
                instant(rs.getTimestamp("revoked_at")),anchor);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
