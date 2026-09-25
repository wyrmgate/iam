package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.RoleExpansionFactSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class JdbcRoleExpansionFactSink
        implements RoleExpansionFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcRoleExpansionFactSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void changed(
            TenantContext tenant,
            UUID roleId,
            long roleRevision,
            Instant occurredAt,
            UUID correlationId,
            UUID causationId) {
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        ROLE_EXPANSION_CHANGED,
                        1,
                        "role",
                        roleId,
                        roleRevision,
                        occurredAt,
                        correlationId == null ? eventId : correlationId,
                        causationId,
                        "{}"),
                occurredAt);
    }
}
