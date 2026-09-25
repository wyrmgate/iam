package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.DesiredPrincipalFactSink;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPrincipalState;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class JdbcDesiredPrincipalFactSink implements DesiredPrincipalFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcDesiredPrincipalFactSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void changed(TenantContext tenant, DesiredPrincipalState state) {
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        DESIRED_PRINCIPAL_CHANGED,
                        1,
                        "desired-principal",
                        state.id(),
                        state.desiredRevision(),
                        state.computedAt(),
                        eventId,
                        null,
                        "{}"),
                state.computedAt());
    }
}
