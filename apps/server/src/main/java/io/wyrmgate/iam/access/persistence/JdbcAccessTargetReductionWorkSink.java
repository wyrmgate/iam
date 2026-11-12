package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessTargetReductionWorkSink;
import io.wyrmgate.iam.access.domain.AccessTargetReduction;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class JdbcAccessTargetReductionWorkSink
        implements AccessTargetReductionWorkSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcAccessTargetReductionWorkSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void requested(
            TenantContext tenant,
            AccessTargetReduction reduction,
            UUID correlationId,
            UUID causationId) {
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        REDUCTION_REQUESTED,
                        1,
                        "access-target-reduction",
                        reduction.id(),
                        reduction.revision(),
                        reduction.updatedAt(),
                        correlationId == null ? eventId : correlationId,
                        causationId,
                        "{}"),
                reduction.updatedAt());
    }
}
