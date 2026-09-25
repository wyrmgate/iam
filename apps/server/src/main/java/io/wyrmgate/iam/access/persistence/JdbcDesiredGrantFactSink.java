package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.DesiredGrantFactSink;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredGrantState;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class JdbcDesiredGrantFactSink implements DesiredGrantFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcDesiredGrantFactSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void changed(TenantContext tenant, DesiredGrantState state) {
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        DESIRED_GRANT_CHANGED,
                        1,
                        "desired-grant",
                        state.id(),
                        state.desiredRevision(),
                        state.computedAt(),
                        eventId,
                        null,
                        "{}"),
                state.computedAt());
    }
}
