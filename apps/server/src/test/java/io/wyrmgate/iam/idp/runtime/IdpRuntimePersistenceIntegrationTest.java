package io.wyrmgate.iam.idp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import io.wyrmgate.iam.idp.session.IdpBrowserSession;
import io.wyrmgate.iam.idp.session.JdbcIdpBrowserSessionRepository;
import io.wyrmgate.iam.idp.protocol.IdpAuthorizationCode;
import io.wyrmgate.iam.idp.protocol.JdbcIdpAuthorizationCodeRepository;
import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IdpRuntimePersistenceIntegrationTest {
    private static JdbcTemplate jdbc;

    @BeforeAll static void start() {
        jdbc = SharedPostgresIntegrationDatabase.jdbc();
    }

    private TenantContext tenant() {
        return new TenantContext(new TenantTestFixture(jdbc, UUID::randomUUID)
                .createActive("IdP runtime test", Instant.now()).id());
    }

    @Test void sharedAdmissionBudgetIsAtomicAcrossInstancesAndResetsForward() throws Exception {
        var tenant = tenant();
        var principal = UUID.randomUUID();
        var first = new JdbcIdpAttemptLimiter(jdbc);
        var second = new JdbcIdpAttemptLimiter(jdbc);
        var kind = IdpAttemptLimiter.Kind.PRINCIPAL_LOGIN;
        // Use a long window and account for the legitimate boundary crossing if it occurs.
        long before = window();
        int admitted = 0;
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 20; i++) {
                var limiter = i % 2 == 0 ? first : second;
                tasks.add(pool.submit(() -> limiter.acquire(tenant, kind, principal)));
            }
            for (var result : tasks) if (result.get(20, TimeUnit.SECONDS)) admitted++;
        }
        if (before == window()) {
            assertThat(admitted).isEqualTo(10);
            assertThat(first.acquire(tenant, kind, principal)).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM platform.idp_attempt_budget WHERE tenant_id=? AND kind=? AND subject_id=?",
                Integer.class, tenant.tenantId(), kind.name(), principal)).isBetween(1, 10);
        assertThat(second.acquire(tenant(), kind, principal)).isTrue();
        jdbc.update("UPDATE platform.idp_attempt_budget SET window_start=clock_timestamp()-interval '1 hour' WHERE tenant_id=?", tenant.tenantId());
        assertThat(second.acquire(tenant, kind, principal)).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM platform.idp_attempt_budget WHERE tenant_id=? AND kind=? AND subject_id=?",
                Integer.class, tenant.tenantId(), kind.name(), principal)).isEqualTo(1);
    }

    @Test void cleanupIsBoundedAndPreservesLiveOrReferencedSessions() {
        var tenant = tenant();
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        var sessions = new JdbcIdpBrowserSessionRepository(jdbc);
        var codes = new JdbcIdpAuthorizationCodeRepository(jdbc);
        var old = session(now.minusSeconds(172800), 1);
        var referenced = session(now.minusSeconds(172800), 2);
        var live = session(now.minusSeconds(60), 3);
        sessions.insert(tenant, old);
        sessions.insert(tenant, referenced);
        sessions.insert(tenant, live);
        var expiredCode = code(old, now.minusSeconds(7200), now.minusSeconds(6900), 1);
        var liveCode = code(referenced, now.minusSeconds(30), now.plusSeconds(270), 2);
        codes.insert(tenant, expiredCode);
        codes.insert(tenant, liveCode);

        jdbc.update("""
                INSERT INTO platform.idp_login_interaction (interaction_hash,browser_hash,tenant_id,
                    client_registration_id,client_revision,authentication_target_id,client_id,redirect_uri,
                    scopes,state_value,pkce_challenge,expires_at)
                SELECT lpad(to_hex(i),64,'0'), lpad(to_hex(i+1000),64,'0'), ?, ?, 1, ?,
                    'client','https://example.test/callback','openid','state',repeat('A',43),clock_timestamp()-interval '1 hour'
                FROM generate_series(1,201) i
                """, tenant.tenantId(), UUID.randomUUID(), UUID.randomUUID());
        new IdpRuntimeCleanup(jdbc).clean();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.idp_login_interaction WHERE tenant_id=?",
                Integer.class, tenant.tenantId())).isEqualTo(1);
        assertThat(sessions.findById(tenant, old.id())).isEmpty();
        assertThat(sessions.findById(tenant, referenced.id())).isPresent();
        assertThat(sessions.findById(tenant, live.id())).isPresent();
        assertThat(codes.consume(tenant, liveCode.codeHash(), now)).isPresent();
        new IdpRuntimeCleanup(jdbc).clean();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.idp_login_interaction WHERE tenant_id=?",
                Integer.class, tenant.tenantId())).isZero();
    }

    @Test void jdbcSessionTouchCannotRegressOrReviveIdleExpiry() {
        var tenant = tenant();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var sessions = new JdbcIdpBrowserSessionRepository(jdbc);
        var session = session(now, 4);
        sessions.insert(tenant, session);
        assertThat(sessions.touch(tenant, session.id(), now.plusSeconds(100), now.plusSeconds(600))).isTrue();
        assertThat(sessions.touch(tenant, session.id(), now.plusSeconds(50), now.plusSeconds(300))).isTrue();
        var current = sessions.findById(tenant, session.id()).orElseThrow();
        assertThat(current.lastSeenAt()).isEqualTo(now.plusSeconds(100));
        assertThat(current.idleExpiresAt()).isEqualTo(now.plusSeconds(600));
        assertThat(sessions.touch(tenant, session.id(), now.plusSeconds(600), now.plusSeconds(1000))).isFalse();
    }

    private static long window() {
        return jdbc.queryForObject("SELECT floor(extract(epoch from clock_timestamp())/300)::bigint", Long.class);
    }

    private static IdpBrowserSession session(Instant created, int hash) {
        return new IdpBrowserSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, 1L, 1L, 1L, String.format("%064x", hash), created, created,
                created.plusSeconds(300), created.plusSeconds(3600), null);
    }

    private static IdpAuthorizationCode code(IdpBrowserSession session, Instant created, Instant expires, int hash) {
        return new IdpAuthorizationCode(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), "client",
                session.id(), session.principalId(), session.identityId(), String.format("%064x", hash),
                "https://example.test/callback", Set.of(SsoClientScope.OPENID), "A".repeat(43), null,
                session.createdAt(), created, expires, null);
    }
}
