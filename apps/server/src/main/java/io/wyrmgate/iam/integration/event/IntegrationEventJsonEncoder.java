package io.wyrmgate.iam.integration.event;

import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.CreatedV1;
import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.Event;
import io.wyrmgate.iam.integration.event.IdentityPublicIntegrationEvents.MetadataChangedV1;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/** Deterministic JSON encoding for the checked-in Identity AsyncAPI v1 contract. */
public final class IntegrationEventJsonEncoder {

    public OutboundIntegrationEvent encode(Event event) {
        String json = switch (event) {
            case CreatedV1 created -> encodeCreated(created);
            case MetadataChangedV1 changed -> encodeMetadataChanged(changed);
        };
        return new OutboundIntegrationEvent(
                event.address(),
                "application/json",
                event.eventId(),
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeCreated(CreatedV1 event) {
        return envelopePrefix(event)
                + ",\"payload\":{"
                + "\"identityType\":\"" + event.payload().identityType().name() + "\","
                + "\"lifecycleState\":\"" + event.payload().lifecycleState().name() + "\""
                + "}}";
    }

    private static String encodeMetadataChanged(MetadataChangedV1 event) {
        String fields = event.payload().changedFields().stream()
                .map(field -> "\"" + field.wireName() + "\"")
                .collect(Collectors.joining(","));
        return envelopePrefix(event)
                + ",\"payload\":{\"changedFields\":[" + fields + "]}}";
    }

    private static String envelopePrefix(Event event) {
        return "{"
                + "\"eventId\":\"" + event.eventId() + "\","
                + "\"eventType\":\"" + event.eventType() + "\","
                + "\"eventVersion\":" + event.eventVersion() + ","
                + "\"occurredAt\":\"" + event.occurredAt() + "\","
                + "\"tenantId\":\"" + event.tenantId() + "\","
                + "\"resource\":{"
                + "\"type\":\"" + event.resource().type() + "\","
                + "\"id\":\"" + event.resource().id() + "\","
                + "\"revision\":" + event.resource().revision()
                + "},"
                + "\"correlationId\":\"" + event.correlationId() + "\","
                + "\"causationId\":" + nullableUuid(event.causationId());
    }

    private static String nullableUuid(java.util.UUID value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
