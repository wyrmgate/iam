package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.integration.application.IntegrationObservedAccessFactSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class JdbcIntegrationObservedAccessFactSink
        implements IntegrationObservedAccessFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcIntegrationObservedAccessFactSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
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
