package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.DesiredStateProcessingService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class DesiredStateProcessingScheduler {

    private final DesiredStateProcessingService service;

    public DesiredStateProcessingScheduler(DesiredStateProcessingService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.access.desired-state.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
