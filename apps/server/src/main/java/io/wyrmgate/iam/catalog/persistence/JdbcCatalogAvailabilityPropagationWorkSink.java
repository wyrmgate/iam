package io.wyrmgate.iam.catalog.persistence;

import io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationWorkSink;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityPropagation;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Transactional outbox continuation for Catalog-owned availability propagation. */
public final class JdbcCatalogAvailabilityPropagationWorkSink
        implements CatalogAvailabilityPropagationWorkSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcCatalogAvailabilityPropagationWorkSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void requested(
            TenantContext tenant,
            CatalogAvailabilityPropagation propagation,
            UUID correlationId,
            UUID causationId) {
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        PROPAGATION_REQUESTED,
                        1,
                        "catalog-availability-propagation",
                        propagation.id(),
                        propagation.revision(),
                        propagation.updatedAt(),
                        correlationId == null ? eventId : correlationId,
                        causationId,
                        "{}"),
                propagation.updatedAt());
    }
}
