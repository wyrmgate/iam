package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.integration.application.IntegrationObservedAccessFactSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;
import java.util.UUID;

public final class JdbcIntegrationObservedAccessFactSink
        implements IntegrationObservedAccessFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;
    private final JdbcTemplate jdbc;

    public JdbcIntegrationObservedAccessFactSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids, JdbcTemplate jdbc) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void inputChanged(
            TenantContext tenant,
            UUID connectorBindingId,
            SourceKind sourceKind,
            UUID sourceId,
            long sourceRevision,
            Instant occurredAt,
            UUID correlationId) {
        jdbc.update("""
                INSERT INTO integration.observed_access_generation (tenant_id, connector_binding_id, generation)
                VALUES (?, ?, 1)
                ON CONFLICT (tenant_id, connector_binding_id) DO UPDATE
                    SET generation = integration.observed_access_generation.generation + 1
                """, tenant.tenantId(), connectorBindingId);
        String payload = "{\"connectorBindingId\":\""
                + connectorBindingId
                + "\"}";
        outbox.append(
                tenant,
                new OutboxEvent(
                        ids.nextId(),
                        OBSERVED_ACCESS_INPUT_CHANGED,
                        1,
                        sourceKind.aggregateType(),
                        sourceId,
                        sourceRevision,
                        occurredAt,
                        correlationId,
                        null,
                        payload),
                occurredAt);
    }
}
