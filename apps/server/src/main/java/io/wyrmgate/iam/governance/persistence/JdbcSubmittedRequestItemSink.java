package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemState;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.governance.application.SubmittedRequestItemSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class JdbcSubmittedRequestItemSink
        implements SubmittedRequestItemSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcSubmittedRequestItemSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void submitted(
            TenantContext tenant,
            RequestItem item) {
        if (item.state() != ItemState.SUBMITTED) {
            throw new IllegalArgumentException(
                    "only SUBMITTED RequestItem can publish evaluation work");
        }
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        REQUEST_ITEM_SUBMITTED,
                        1,
                        "request-item",
                        item.id(),
                        item.revision(),
                        item.updatedAt(),
                        eventId,
                        null,
                        "{}"),
                item.updatedAt());
    }
}
