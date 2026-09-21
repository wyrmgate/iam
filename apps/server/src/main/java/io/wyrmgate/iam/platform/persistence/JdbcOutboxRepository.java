package io.wyrmgate.iam.platform.persistence;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for transactional outbox facts and short-lived publication leases. */
public final class JdbcOutboxRepository {

    private static final int MAX_CLAIM_BATCH = 500;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    public void append(TenantContext tenant, OutboxEvent event, Instant recordedAt) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(recordedAt, "recordedAt");

        jdbcTemplate.update(
                """
                INSERT INTO platform.outbox_event (
                    id, tenant_id, event_type, event_version,
                    aggregate_type, aggregate_id, aggregate_revision,
                    occurred_at, correlation_id, causation_id,
                    payload, publication_state, attempt_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING', 0, ?)
                """,
                event.eventId(),
                tenant.tenantId(),
                event.eventType(),
                event.eventVersion(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateRevision(),
                JdbcValues.timestamp(event.occurredAt()),
                event.correlationId(),
                event.causationId(),
                event.payloadJson(),
                JdbcValues.timestamp(recordedAt));
    }

    public List<ClaimedOutboxEvent> claimPending(
            Set<String> eventTypes,
            Instant now,
            Duration leaseDuration,
            int limit) {
        Objects.requireNonNull(eventTypes, "eventTypes");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (eventTypes.isEmpty() || eventTypes.stream().anyMatch(type -> type == null || type.isBlank())) {
            throw new IllegalArgumentException("eventTypes must contain non-blank values");
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (limit < 1 || limit > MAX_CLAIM_BATCH) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_CLAIM_BATCH);
        }

        List<String> types = eventTypes.stream().sorted().toList();
        String placeholders = String.join(", ", types.stream().map(ignored -> "?").toList());
        String sql = """
                WITH candidates AS (
                    SELECT id
                    FROM platform.outbox_event
                    WHERE publication_state = 'PENDING'
                      AND event_type IN (%s)
                      AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                    ORDER BY COALESCE(next_attempt_at, occurred_at), occurred_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE platform.outbox_event AS event
                SET attempt_count = event.attempt_count + 1,
                    last_attempt_at = ?,
                    next_attempt_at = ?
                FROM candidates
                WHERE event.id = candidates.id
                RETURNING
                    event.id,
                    event.tenant_id,
                    event.event_type,
                    event.event_version,
                    event.aggregate_type,
                    event.aggregate_id,
                    event.aggregate_revision,
                    event.occurred_at,
                    event.correlation_id,
                    event.causation_id,
                    event.payload::text AS payload_json,
                    event.attempt_count
                """.formatted(placeholders);

        Instant leaseUntil = now.plus(leaseDuration);
        return jdbcTemplate.query(
                connection -> {
                    PreparedStatement statement = connection.prepareStatement(sql);
                    int index = 1;
                    for (String type : types) {
                        statement.setString(index++, type);
                    }
                    statement.setObject(index++, JdbcValues.timestamp(now));
                    statement.setInt(index++, limit);
                    statement.setObject(index++, JdbcValues.timestamp(now));
                    statement.setObject(index, JdbcValues.timestamp(leaseUntil));
                    return statement;
                },
                (resultSet, rowNum) -> {
                    Long aggregateRevision = resultSet.getObject("aggregate_revision", Long.class);
                    return new ClaimedOutboxEvent(
                            new TenantContext(resultSet.getObject("tenant_id", java.util.UUID.class)),
                            new OutboxEvent(
                                    resultSet.getObject("id", java.util.UUID.class),
                                    resultSet.getString("event_type"),
                                    resultSet.getInt("event_version"),
                                    resultSet.getString("aggregate_type"),
                                    resultSet.getObject("aggregate_id", java.util.UUID.class),
                                    aggregateRevision,
                                    resultSet.getTimestamp("occurred_at").toInstant(),
                                    resultSet.getObject("correlation_id", java.util.UUID.class),
                                    resultSet.getObject("causation_id", java.util.UUID.class),
                                    resultSet.getString("payload_json")),
                            resultSet.getInt("attempt_count"));
                });
    }

    public boolean markPublished(
            TenantContext tenant,
            java.util.UUID eventId,
            Instant publishedAt) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        return jdbcTemplate.update(
                """
                UPDATE platform.outbox_event
                SET publication_state = 'PUBLISHED',
                    published_at = ?,
                    next_attempt_at = NULL,
                    last_error_code = NULL
                WHERE tenant_id = ?
                  AND id = ?
                  AND publication_state = 'PENDING'
                """,
                JdbcValues.timestamp(publishedAt),
                tenant.tenantId(),
                eventId) == 1;
    }

    public boolean markFailed(
            TenantContext tenant,
            java.util.UUID eventId,
            Instant nextAttemptAt,
            String errorCode) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 128) {
            throw new IllegalArgumentException("errorCode must contain between 1 and 128 characters");
        }
        return jdbcTemplate.update(
                """
                UPDATE platform.outbox_event
                SET next_attempt_at = ?,
                    last_error_code = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND publication_state = 'PENDING'
                """,
                JdbcValues.timestamp(nextAttemptAt),
                errorCode,
                tenant.tenantId(),
                eventId) == 1;
    }

    public long countPending(TenantContext tenant) {
        Objects.requireNonNull(tenant, "tenant");
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ? AND publication_state = 'PENDING'",
                Long.class,
                tenant.tenantId());
        return count == null ? 0 : count;
    }
}
