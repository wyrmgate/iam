package io.wyrmgate.iam.integration.event;

import java.util.Objects;
import java.util.UUID;

/** Encoded transport-neutral public event ready for an external delivery adapter. */
public record OutboundIntegrationEvent(
        String address,
        String contentType,
        UUID eventId,
        byte[] payload) {

    public OutboundIntegrationEvent {
        requireText(address, "address");
        requireText(contentType, "contentType");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
