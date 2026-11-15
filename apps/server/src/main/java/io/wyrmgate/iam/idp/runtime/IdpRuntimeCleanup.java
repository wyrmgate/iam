package io.wyrmgate.iam.idp.runtime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded technical deletion only; Audit evidence is independently retained. */
final class IdpRuntimeCleanup {
    private final JdbcTemplate jdbc;
    IdpRuntimeCleanup(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelayString = "${iam.idp.cleanup.interval:PT1M}")
    void clean() {
        jdbc.update("DELETE FROM platform.idp_federation_interaction WHERE id IN (SELECT id FROM platform.idp_federation_interaction WHERE expires_at<=clock_timestamp() ORDER BY expires_at,id LIMIT 200 FOR UPDATE SKIP LOCKED)");
        jdbc.update("""
                DELETE FROM platform.idp_login_interaction
                WHERE interaction_hash IN (
                    SELECT interaction_hash FROM platform.idp_login_interaction
                    WHERE expires_at <= clock_timestamp()
                    ORDER BY expires_at, interaction_hash LIMIT 200 FOR UPDATE SKIP LOCKED)
                """);
        jdbc.update("""
                DELETE FROM platform.idp_authorization_code WHERE id IN (
                    SELECT id FROM platform.idp_authorization_code
                    WHERE expires_at <= clock_timestamp() - interval '1 hour'
                       OR consumed_at <= clock_timestamp() - interval '1 hour'
                    ORDER BY expires_at, id LIMIT 200 FOR UPDATE SKIP LOCKED)
                """);
        jdbc.update("""
                DELETE FROM platform.idp_browser_session s WHERE id IN (
                    SELECT candidate.id FROM platform.idp_browser_session candidate
                    WHERE (candidate.revoked_at <= clock_timestamp() - interval '24 hours'
                        OR candidate.idle_expires_at <= clock_timestamp() - interval '24 hours'
                        OR candidate.absolute_expires_at <= clock_timestamp() - interval '24 hours')
                      AND NOT EXISTS (SELECT 1 FROM platform.idp_authorization_code c
                          WHERE c.tenant_id = candidate.tenant_id AND c.browser_session_id = candidate.id)
                    ORDER BY candidate.absolute_expires_at, candidate.id LIMIT 200 FOR UPDATE SKIP LOCKED)
                """);
        jdbc.update("""
                DELETE FROM platform.idp_attempt_budget b WHERE (tenant_id, kind, subject_id) IN (
                    SELECT tenant_id, kind, subject_id FROM platform.idp_attempt_budget
                    WHERE window_start <= clock_timestamp() - interval '24 hours'
                    ORDER BY window_start, tenant_id, kind, subject_id LIMIT 200 FOR UPDATE SKIP LOCKED)
                """);
    }
}
