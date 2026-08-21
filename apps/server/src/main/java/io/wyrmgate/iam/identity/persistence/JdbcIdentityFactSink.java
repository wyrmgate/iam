package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.IdentityFactSink;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Maps internal Identity facts to the shared transactional outbox envelope. */
public final class JdbcIdentityFactSink implements IdentityFactSink {

    private final JdbcOutboxRepository outboxRepository;
    private final IdGenerator idGenerator;

    public JdbcIdentityFactSink(JdbcOutboxRepository outboxRepository, IdGenerator idGenerator) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    @Override
    public void identityCreated(
            TenantContext tenant,
            Identity identity,
            UUID correlationId,
            UUID causationId) {
        append(
                tenant,
                identity,
                "identity.identity-created",
                "{\"identityType\":\"" + identity.type().name() + "\"}",
                correlationId,
                causationId);
    }

    @Override
    public void displayNameChanged(
            TenantContext tenant,
            Identity identity,
            UUID correlationId,
            UUID causationId) {
        append(
                tenant,
                identity,
                "identity.identity-display-name-changed",
                "{\"displayNameChanged\":true}",
                correlationId,
                causationId);
    }

    private void append(
            TenantContext tenant,
            Identity identity,
            String eventType,
            String payloadJson,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(correlationId, "correlationId");

        outboxRepository.append(
                tenant,
                new OutboxEvent(
                        idGenerator.nextId(),
                        eventType,
                        1,
                        "identity",
                        identity.id(),
                        identity.revision(),
                        identity.updatedAt(),
                        correlationId,
                        causationId,
                        payloadJson),
                identity.updatedAt());
    }
}
