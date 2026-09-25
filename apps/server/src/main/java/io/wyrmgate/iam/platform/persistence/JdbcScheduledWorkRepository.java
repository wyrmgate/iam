package io.wyrmgate.iam.platform.persistence;

import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Technical scheduling/lease adapter; domain process state remains capability-owned. */
public final class JdbcScheduledWorkRepository {

    private final JdbcTemplate jdbcTemplate;
    private final IdGenerator idGenerator;

    public JdbcScheduledWorkRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    public boolean enqueue(
            TenantContext tenant,
            String handlerType,
            String workKey,
            SubjectReference subject,
            Instant availableAt,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        requireText(handlerType, "handlerType");
        requireText(workKey, "workKey");
        Objects.requireNonNull(availableAt, "availableAt");
        Objects.requireNonNull(now, "now");

        int affected = jdbcTemplate.update(
                """
                INSERT INTO platform.scheduled_work (
                    id, tenant_id, handler_type, work_key,
                    subject_type, subject_id, subject_revision,
                    available_at, delivery_state, attempt_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', 0, ?, ?)
                ON CONFLICT (tenant_id, handler_type, work_key) DO NOTHING
                """,
                idGenerator.nextId(),
                tenant.tenantId(),
                handlerType,
                workKey,
                subject == null ? null : subject.subjectType(),
                subject == null ? null : subject.subjectId(),
                subject == null ? null : subject.subjectRevision(),
                JdbcValues.timestamp(availableAt),
                JdbcValues.timestamp(now),
                JdbcValues.timestamp(now));
        return affected == 1;
    }

    public List<ClaimedWork> claimDue(
            TenantContext tenant,
            String leaseOwner,
            Instant now,
            Duration leaseDuration,
            int limit) {
        Objects.requireNonNull(tenant, "tenant");
        requireText(leaseOwner, "leaseOwner");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }

        Instant leaseUntil = now.plus(leaseDuration);
        return jdbcTemplate.query(
                """
                WITH due AS (
                    SELECT id
                    FROM platform.scheduled_work
                    WHERE tenant_id = ?
                      AND delivery_state = 'READY'
                      AND available_at <= ?
                      AND (lease_until IS NULL OR lease_until <= ?)
                    ORDER BY available_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE platform.scheduled_work AS work
                SET lease_owner = ?,
                    lease_until = ?,
                    attempt_count = attempt_count + 1,
                    updated_at = ?
                FROM due
                WHERE work.id = due.id AND work.tenant_id = ?
                RETURNING work.id, work.handler_type, work.work_key,
                          work.subject_type, work.subject_id, work.subject_revision,
                          work.attempt_count, work.lease_until
                """,
                (rs, rowNum) -> new ClaimedWork(
                        rs.getObject("id", UUID.class),
                        rs.getString("handler_type"),
                        rs.getString("work_key"),
                        rs.getString("subject_type") == null
                                ? null
                                : new SubjectReference(
                                        rs.getString("subject_type"),
                                        rs.getObject("subject_id", UUID.class),
                                        rs.getLong("subject_revision")),
                        rs.getInt("attempt_count"),
                        rs.getTimestamp("lease_until").toInstant()),
                tenant.tenantId(),
                JdbcValues.timestamp(now),
                JdbcValues.timestamp(now),
                limit,
                leaseOwner,
                JdbcValues.timestamp(leaseUntil),
                JdbcValues.timestamp(now),
                tenant.tenantId());
    }

    public List<ClaimedTenantWork> claimDueByHandler(
            String handlerType,
            String leaseOwner,
            Instant now,
            Duration leaseDuration,
            int limit) {
        requireText(handlerType, "handlerType");
        requireText(leaseOwner, "leaseOwner");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }

        Instant leaseUntil = now.plus(leaseDuration);
        return jdbcTemplate.query(
                """
                WITH due AS (
                    SELECT id
                    FROM platform.scheduled_work
                    WHERE handler_type = ?
                      AND delivery_state = 'READY'
                      AND available_at <= ?
                      AND (lease_until IS NULL OR lease_until <= ?)
                    ORDER BY available_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE platform.scheduled_work AS work
                SET lease_owner = ?,
                    lease_until = ?,
                    attempt_count = attempt_count + 1,
                    updated_at = ?
                FROM due
                WHERE work.id = due.id
                RETURNING work.id, work.tenant_id, work.handler_type, work.work_key,
                          work.subject_type, work.subject_id, work.subject_revision,
                          work.attempt_count, work.lease_until
                """,
                (rs, rowNum) -> new ClaimedTenantWork(
                        new TenantContext(rs.getObject("tenant_id", UUID.class)),
                        new ClaimedWork(
                                rs.getObject("id", UUID.class),
                                rs.getString("handler_type"),
                                rs.getString("work_key"),
                                rs.getString("subject_type") == null
                                        ? null
                                        : new SubjectReference(
                                                rs.getString("subject_type"),
                                                rs.getObject("subject_id", UUID.class),
                                                rs.getLong("subject_revision")),
                                rs.getInt("attempt_count"),
                                rs.getTimestamp("lease_until").toInstant())),
                handlerType,
                JdbcValues.timestamp(now),
                JdbcValues.timestamp(now),
                limit,
                leaseOwner,
                JdbcValues.timestamp(leaseUntil),
                JdbcValues.timestamp(now));
    }

    public void markCompleted(
            TenantContext tenant,
            UUID workId,
            String leaseOwner,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(workId, "workId");
        requireText(leaseOwner, "leaseOwner");
        Objects.requireNonNull(now, "now");

        int affected = jdbcTemplate.update(
                """
                UPDATE platform.scheduled_work
                SET delivery_state = 'COMPLETED', lease_owner = NULL, lease_until = NULL, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND delivery_state = 'READY'
                  AND lease_owner = ? AND lease_until > ?
                """,
                JdbcValues.timestamp(now),
                tenant.tenantId(),
                workId,
                leaseOwner,
                JdbcValues.timestamp(now));
        if (affected != 1) {
            throw new IllegalStateException("Scheduled work is not held by an active lease for the expected owner");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record SubjectReference(String subjectType, UUID subjectId, long subjectRevision) {
        public SubjectReference {
            requireText(subjectType, "subjectType");
            Objects.requireNonNull(subjectId, "subjectId");
            if (subjectRevision < 1) {
                throw new IllegalArgumentException("subjectRevision must be positive");
            }
        }
    }

    public record ClaimedWork(
            UUID id,
            String handlerType,
            String workKey,
            SubjectReference subject,
            int attemptCount,
            Instant leaseUntil) {
    }

    public record ClaimedTenantWork(
            TenantContext tenant,
            ClaimedWork work) {
        public ClaimedTenantWork {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(work, "work");
        }
    }
}
