package io.wyrmgate.iam.catalog.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityFactSink;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityCauseKind;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Transactional outbox implementation for Catalog target-availability change triggers. */
public final class JdbcCatalogAccessTargetAvailabilityFactSink
        implements CatalogAccessTargetAvailabilityFactSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;
    private final ObjectMapper json;

    public JdbcCatalogAccessTargetAvailabilityFactSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids,
            ObjectMapper json) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public void changed(
            TenantContext tenant,
            CatalogAccessTargetKind targetKind,
            UUID targetId,
            CatalogAvailabilityCauseKind causeKind,
            UUID causeId,
            long causeRevision,
            Instant occurredAt,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(causeKind, "causeKind");
        Objects.requireNonNull(causeId, "causeId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (causeRevision < 1) {
            throw new IllegalArgumentException("causeRevision must be positive");
        }

        UUID eventId = ids.nextId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetKind", targetKind.name());
        payload.put("targetId", targetId.toString());
        payload.put("causeKind", causeKind.name());
        payload.put("causeId", causeId.toString());
        payload.put("causeRevision", causeRevision);

        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        ACCESS_TARGET_AVAILABILITY_CHANGED,
                        1,
                        causeKind.name().toLowerCase().replace('_', '-'),
                        causeId,
                        causeRevision,
                        occurredAt,
                        correlationId == null ? eventId : correlationId,
                        causationId,
                        encode(payload)),
                occurredAt);
    }

    private String encode(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(
                    "Catalog availability payload could not be encoded", impossible);
        }
    }
}
