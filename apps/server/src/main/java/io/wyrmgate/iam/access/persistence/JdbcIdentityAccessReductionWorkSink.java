package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.IdentityAccessReductionWorkSink;
import io.wyrmgate.iam.access.domain.IdentityAccessReduction;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class JdbcIdentityAccessReductionWorkSink
        implements IdentityAccessReductionWorkSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcIdentityAccessReductionWorkSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void reductionRequested(
            TenantContext tenant,
            IdentityAccessReduction reduction,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(reduction, "reduction");
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        REDUCTION_REQUESTED,
                        1,
                        "identity-access-reduction",
                        reduction.id(),
                        reduction.revision(),
                        reduction.updatedAt(),
                        correlationId == null
                                ? eventId
                                : correlationId,
                        causationId,
                        "{}"),
                reduction.updatedAt());
    }
}
