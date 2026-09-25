package io.wyrmgate.iam.access.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class EffectiveAccessProcessingScheduler {

    private final EffectiveAccessProcessingService service;

    public EffectiveAccessProcessingScheduler(
            EffectiveAccessProcessingService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.access.effective-access.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
