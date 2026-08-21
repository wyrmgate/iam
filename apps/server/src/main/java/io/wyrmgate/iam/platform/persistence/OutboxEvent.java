package io.wyrmgate.iam.platform.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Technical outbox envelope for a completed semantic fact. */
public record OutboxEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        UUID aggregateId,
        Long aggregateRevision,
        Instant occurredAt,
        UUID correlationId,
        UUID causationId,
        String payloadJson) {

    public OutboxEvent {
        Objects.requireNonNull(eventId, "eventId");
        requireText(eventType, "eventType");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be positive");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireText(payloadJson, "payloadJson");

        boolean noAggregate = aggregateType == null && aggregateId == null && aggregateRevision == null;
        boolean completeAggregate = aggregateType != null
                && !aggregateType.isBlank()
                && aggregateId != null
                && aggregateRevision != null
                && aggregateRevision > 0;
        if (!noAggregate && !completeAggregate) {
            throw new IllegalArgumentException("aggregate reference must be absent or complete");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
