package io.wyrmgate.iam.authentication.persistence;

import io.wyrmgate.iam.authentication.application.AuthenticationProtocolQuery;
import io.wyrmgate.iam.authentication.application.AuthenticationRepository;
import io.wyrmgate.iam.authentication.domain.AuthenticationClient;
import io.wyrmgate.iam.authentication.domain.AuthenticationSession;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC protocol-edge lookup. Tenant context is derived from server-owned Authentication state. */
public final class JdbcAuthenticationProtocolQuery implements AuthenticationProtocolQuery {

    private final JdbcTemplate jdbc;
    private final AuthenticationRepository repository;

    public JdbcAuthenticationProtocolQuery(
            JdbcTemplate jdbc,
            AuthenticationRepository repository) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<ResolvedClient> findActiveClientByProtocolClientId(String protocolClientId) {
        if (protocolClientId == null || protocolClientId.isBlank()) return Optional.empty();
        return jdbc.query("""
                SELECT tenant_id, id
                FROM authentication.client
                WHERE protocol_client_id = ? AND lifecycle_state = 'ACTIVE'
                """, (rs, n) -> new Key(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class)), protocolClientId)
                .stream()
                .findFirst()
                .flatMap(key -> {
                    TenantContext tenant = new TenantContext(key.tenantId());
                    return repository.findClient(tenant, key.id())
                            .filter(client -> client.lifecycleState() == AuthenticationClient.LifecycleState.ACTIVE)
                            .map(client -> new ResolvedClient(tenant, client));
                });
    }

    @Override
    public Optional<ResolvedSession> findActiveSessionBySecretDigest(String sessionSecretDigest) {
        if (sessionSecretDigest == null || sessionSecretDigest.isBlank()) return Optional.empty();
        Instant now = Instant.now();
        return jdbc.query("""
                SELECT tenant_id, id
                FROM authentication.session
                WHERE session_secret_digest = ?
                  AND lifecycle_state = 'ACTIVE'
                  AND expires_at > ?
                """, (rs, n) -> new Key(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class)), sessionSecretDigest, java.sql.Timestamp.from(now))
                .stream()
                .findFirst()
                .flatMap(key -> {
                    TenantContext tenant = new TenantContext(key.tenantId());
                    return repository.findSession(tenant, key.id())
                            .filter(session -> session.effectiveAt(now))
                            .map(session -> new ResolvedSession(tenant, session));
                });
    }

    private record Key(UUID tenantId, UUID id) {}
}