package io.wyrmgate.iam.platform.persistence;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC append adapter for transactional outbox facts. */
public final class JdbcOutboxRepository {

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
                event.occurredAt(),
                event.correlationId(),
                event.causationId(),
                event.payloadJson(),
                recordedAt);
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
