package io.wyrmgate.iam.governance.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class ReviewGenerationProcessingScheduler {

    private final ReviewGenerationProcessingService service;

    public ReviewGenerationProcessingScheduler(
            ReviewGenerationProcessingService service) {
        this.service = Objects.requireNonNull(
                service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.governance.review-generation.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
