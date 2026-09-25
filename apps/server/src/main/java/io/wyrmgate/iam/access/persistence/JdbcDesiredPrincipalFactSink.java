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
        append(tenant, state, DESIRED_PRINCIPAL_CHANGED);
    }

    @Override
    public void revalidate(TenantContext tenant, DesiredPrincipalState state) {
        append(tenant, state, DESIRED_PRINCIPAL_REVALIDATE);
    }

    private void append(
            TenantContext tenant,
            DesiredPrincipalState state,
            String eventType) {
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        eventType,
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
