package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationRepository;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassNotificationWork;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassRepository;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC delivery-state persistence for ADR-0033 SECURITY_NOTIFICATION obligations. */
public final class JdbcAdministrativeBreakGlassNotificationRepository
        implements AdministrativeBreakGlassNotificationRepository {

    private final JdbcTemplate jdbc;
    private final AdministrativeBreakGlassRepository breakGlass;

    public JdbcAdministrativeBreakGlassNotificationRepository(
            JdbcTemplate jdbc,
            AdministrativeBreakGlassRepository breakGlass) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.breakGlass = java.util.Objects.requireNonNull(breakGlass, "breakGlass");
    }

    @Override
    public List<AdministrativeBreakGlassNotificationWork> claimPending(
            Instant now, Duration leaseDuration, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }

        Instant leaseUntil = now.plus(leaseDuration);
        List<Claim> claims = jdbc.query(
                """
                WITH due AS (
                    SELECT tenant_id, id
                    FROM administration.administrative_break_glass_obligation
                    WHERE obligation_type = 'SECURITY_NOTIFICATION'
                      AND state = 'PENDING'
                      AND notification_next_attempt_at <= ?
                      AND (notification_lease_until IS NULL OR notification_lease_until <= ?)
                    ORDER BY notification_next_attempt_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE administration.administrative_break_glass_obligation o
                SET notification_attempt_count = o.notification_attempt_count + 1,
                    notification_lease_until = ?,
                    notification_last_attempt_at = ?,
                    updated_at = ?
                FROM due
                WHERE o.tenant_id = due.tenant_id AND o.id = due.id
                RETURNING o.tenant_id, o.id, o.break_glass_operation_id,
                          o.notification_attempt_count
                """,
                (rs, rowNum) -> new Claim(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getObject("break_glass_operation_id", UUID.class),
                        rs.getInt("notification_attempt_count")),
                Timestamp.from(now),
                Timestamp.from(now),
                limit,
                Timestamp.from(leaseUntil),
                Timestamp.from(now),
                Timestamp.from(now));

        return claims.stream()
                .map(claim -> {
                    TenantContext tenant = new TenantContext(claim.tenantId());
                    var operation = breakGlass.find(tenant, claim.operationId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "claimed break-glass notification operation does not exist"));
                    return new AdministrativeBreakGlassNotificationWork(
                            tenant, claim.obligationId(), claim.attemptCount(), operation);
                })
                .toList();
    }

    @Override
    public void markCompleted(
            TenantContext tenant, UUID obligationId, int expectedAttemptCount, Instant now) {
        int updated = jdbc.update(
                """
                UPDATE administration.administrative_break_glass_obligation
                SET state = 'COMPLETED',
                    completed_at = ?,
                    notification_next_attempt_at = NULL,
                    notification_lease_until = NULL,
                    notification_last_error_code = NULL,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND obligation_type = 'SECURITY_NOTIFICATION'
                  AND state = 'PENDING'
                  AND notification_attempt_count = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                obligationId,
                expectedAttemptCount);
        requireUpdated(updated, "complete", obligationId);
    }

    @Override
    public void markRetry(
            TenantContext tenant,
            UUID obligationId,
            int expectedAttemptCount,
            Instant nextAttemptAt,
            String errorCode,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE administration.administrative_break_glass_obligation
                SET notification_next_attempt_at = ?,
                    notification_lease_until = NULL,
                    notification_last_error_code = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND obligation_type = 'SECURITY_NOTIFICATION'
                  AND state = 'PENDING'
                  AND notification_attempt_count = ?
                """,
                Timestamp.from(nextAttemptAt),
                boundedCode(errorCode),
                Timestamp.from(now),
                tenant.tenantId(),
                obligationId,
                expectedAttemptCount);
        requireUpdated(updated, "retry", obligationId);
    }

    @Override
    public void markManualRequired(
            TenantContext tenant,
            UUID obligationId,
            int expectedAttemptCount,
            String errorCode,
            Instant now) {
        int updated = jdbc.update(
                """
                UPDATE administration.administrative_break_glass_obligation
                SET state = 'MANUAL_REQUIRED',
                    notification_next_attempt_at = NULL,
                    notification_lease_until = NULL,
                    notification_last_error_code = ?,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND obligation_type = 'SECURITY_NOTIFICATION'
                  AND state = 'PENDING'
                  AND notification_attempt_count = ?
                """,
                boundedCode(errorCode),
                Timestamp.from(now),
                tenant.tenantId(),
                obligationId,
                expectedAttemptCount);
        requireUpdated(updated, "manual-required", obligationId);
    }

    private static String boundedCode(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("errorCode must contain between 1 and 128 characters");
        }
        return value;
    }

    private static void requireUpdated(int updated, String action, UUID obligationId) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "break-glass notification " + action + " lost its delivery fence for " + obligationId);
        }
    }

    private record Claim(
            UUID tenantId,
            UUID obligationId,
            UUID operationId,
            int attemptCount) {}
}
