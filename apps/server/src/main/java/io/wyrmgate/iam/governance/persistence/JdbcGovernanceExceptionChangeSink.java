package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.GovernanceExceptionChangeSink;
import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.GovernanceException;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class JdbcGovernanceExceptionChangeSink
        implements GovernanceExceptionChangeSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcGovernanceExceptionChangeSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(
                outbox, "outbox");
        this.ids = Objects.requireNonNull(
                ids, "ids");
    }

    @Override
    public void changed(
            TenantContext tenant,
            GovernanceException exception) {
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        EXCEPTION_CHANGED,
                        1,
                        "governance-exception",
                        exception.id(),
                        exception.revision(),
                        exception.updatedAt(),
                        eventId,
                        null,
                        "{}"),
                exception.updatedAt());
    }
}
