package io.wyrmgate.iam.idp.runtime;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcIdpAttemptLimiter implements IdpAttemptLimiter {
    private final JdbcTemplate jdbc;
    JdbcIdpAttemptLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean acquire(TenantContext tenant, Kind kind, UUID subjectId) {
        if (kind != Kind.PRINCIPAL_LOGIN && !tenant.tenantId().equals(subjectId)) {
            throw new IllegalArgumentException("tenant budget must use tenant ID");
        }
        // Atomic across replicas. One row per trusted subject and kind, reset only forward in time.
        return jdbc.query("""
                INSERT INTO platform.idp_attempt_budget AS b
                    (tenant_id, kind, subject_id, window_start, attempt_count)
                VALUES (?, ?, ?, to_timestamp(floor(extract(epoch from clock_timestamp()) / ?) * ?), 1)
                ON CONFLICT (tenant_id, kind, subject_id) DO UPDATE SET
                    window_start = EXCLUDED.window_start,
                    attempt_count = CASE WHEN b.window_start < EXCLUDED.window_start THEN 1 ELSE b.attempt_count + 1 END
                WHERE b.window_start < EXCLUDED.window_start
                    OR (b.window_start = EXCLUDED.window_start AND b.attempt_count < ?)
                RETURNING attempt_count
                """, (rs, row) -> rs.getInt(1), tenant.tenantId(), kind.name(), subjectId,
                kind.seconds(), kind.seconds(), kind.limit()).size() == 1;
    }
}
