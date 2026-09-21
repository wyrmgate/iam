package io.wyrmgate.iam.integration.event;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical polling trigger only; publication semantics remain in the service. */
final class IntegrationEventPublicationScheduler {

    private final IntegrationEventPublicationService service;

    IntegrationEventPublicationScheduler(IntegrationEventPublicationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.integration.events.poll-interval:PT1S}")
    void publishAvailable() {
        service.publishAvailable();
    }
}
