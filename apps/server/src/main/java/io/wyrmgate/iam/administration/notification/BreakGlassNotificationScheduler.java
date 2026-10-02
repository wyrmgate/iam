package io.wyrmgate.iam.administration.notification;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical polling trigger only; Administration retains notification obligation semantics. */
final class BreakGlassNotificationScheduler {
    private final BreakGlassNotificationDeliveryService service;

    BreakGlassNotificationScheduler(BreakGlassNotificationDeliveryService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.administration.break-glass.notification.poll-interval:PT1S}")
    void deliverAvailable() {
        service.deliverAvailable();
    }
}
