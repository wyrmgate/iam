package io.wyrmgate.iam.platform.persistence;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;

/** One leased outbox fact ready for delivery outside the claiming statement. */
public record ClaimedOutboxEvent(
        TenantContext tenant,
        OutboxEvent event,
        int attemptCount) {

    public ClaimedOutboxEvent {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(event, "event");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
    }
}
