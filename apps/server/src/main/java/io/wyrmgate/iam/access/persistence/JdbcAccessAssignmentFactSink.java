package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessAssignmentFactSink;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class JdbcAccessAssignmentFactSink implements AccessAssignmentFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcAccessAssignmentFactSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void projectionInputChanged(
            TenantContext tenant, AccessAssignment assignment) {
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        PROJECTION_INPUT_CHANGED,
                        1,
                        "access-assignment",
                        assignment.id(),
                        assignment.revision(),
                        assignment.updatedAt(),
                        eventId,
                        null,
                        "{}"),
                assignment.updatedAt());
    }
}
