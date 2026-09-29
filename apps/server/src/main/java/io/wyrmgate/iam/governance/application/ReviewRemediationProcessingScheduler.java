package io.wyrmgate.iam.governance.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class ReviewRemediationProcessingScheduler {

    private final ReviewRemediationProcessingService service;

    public ReviewRemediationProcessingScheduler(
            ReviewRemediationProcessingService service) {
        this.service = Objects.requireNonNull(
                service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.governance.review-remediation.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
