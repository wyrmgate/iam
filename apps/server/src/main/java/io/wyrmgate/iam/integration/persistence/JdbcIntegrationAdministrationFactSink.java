package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.integration.application.IntegrationAdministrationFactSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class JdbcIntegrationAdministrationFactSink
        implements IntegrationAdministrationFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcIntegrationAdministrationFactSink(JdbcOutboxRepository outbox, IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void connectorChanged(
            TenantContext tenant, String factType, UUID connectorId, long revision,
            Instant occurredAt, UUID correlationId) {
        append(tenant, factType, "connector", connectorId, revision, occurredAt, correlationId);
    }

    @Override
    public void bindingChanged(
            TenantContext tenant, String factType, UUID bindingId, long revision,
            Instant occurredAt, UUID correlationId) {
        append(tenant, factType, "connector-binding", bindingId, revision, occurredAt, correlationId);
    }

    @Override
    public void workerChanged(
            TenantContext tenant, String factType, UUID workerId, long revision,
            Instant occurredAt, UUID correlationId) {
        append(tenant, factType, "connector-worker", workerId, revision, occurredAt, correlationId);
    }

    private void append(
            TenantContext tenant, String factType, String aggregateType, UUID aggregateId,
            long revision, Instant occurredAt, UUID correlationId) {
        outbox.append(
                tenant,
                new OutboxEvent(
                        ids.nextId(), factType, 1, aggregateType, aggregateId, revision,
                        occurredAt, correlationId, null, "{}"),
                occurredAt);
    }
}
