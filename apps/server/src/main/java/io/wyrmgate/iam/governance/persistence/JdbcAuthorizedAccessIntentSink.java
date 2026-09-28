package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.governance.application.AuthorizedAccessIntentSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class JdbcAuthorizedAccessIntentSink
        implements AuthorizedAccessIntentSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcAuthorizedAccessIntentSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void authorized(
            TenantContext tenant,
            RequestItem item) {
        if (item.state()
                != io.wyrmgate.iam.governance.application
                        .AccessRequestModels.ItemState.AUTHORIZED) {
            throw new IllegalArgumentException(
                    "only AUTHORIZED RequestItem can publish access intent");
        }
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        REQUEST_ITEM_AUTHORIZED,
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
