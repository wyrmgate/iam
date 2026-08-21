package io.wyrmgate.iam.platform.persistence;

import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Tenant-aware at-least-once consumer deduplication storage. */
public final class JdbcInboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final IdGenerator idGenerator;

    public JdbcInboxRepository(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    public boolean tryAccept(
            TenantContext tenant,
            String consumerName,
            String messageId,
            Instant firstSeenAt) {
        Objects.requireNonNull(tenant, "tenant");
        requireText(consumerName, "consumerName");
        requireText(messageId, "messageId");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");

        int affected = jdbcTemplate.update(
                """
                INSERT INTO platform.inbox_message (
                    id, tenant_id, consumer_name, message_id, processing_state, first_seen_at)
                VALUES (?, ?, ?, ?, 'RECEIVED', ?)
                ON CONFLICT (tenant_id, consumer_name, message_id) DO NOTHING
                """,
                idGenerator.nextId(),
                tenant.tenantId(),
                consumerName,
                messageId,
                firstSeenAt);
        return affected == 1;
    }

    public void markCompleted(
            TenantContext tenant,
            String consumerName,
            String messageId,
            String outcomeCode,
            Instant completedAt) {
        Objects.requireNonNull(tenant, "tenant");
        requireText(consumerName, "consumerName");
        requireText(messageId, "messageId");
        Objects.requireNonNull(completedAt, "completedAt");

        int affected = jdbcTemplate.update(
                """
                UPDATE platform.inbox_message
                SET processing_state = 'COMPLETED', completed_at = ?, outcome_code = ?
                WHERE tenant_id = ? AND consumer_name = ? AND message_id = ?
                  AND processing_state = 'RECEIVED'
                """,
                completedAt,
                outcomeCode,
                tenant.tenantId(),
                consumerName,
                messageId);
        if (affected != 1) {
            throw new IllegalStateException("Inbox message is absent or already completed");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
