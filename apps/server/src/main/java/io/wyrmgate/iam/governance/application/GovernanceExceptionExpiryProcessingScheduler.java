package io.wyrmgate.iam.governance.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class GovernanceExceptionExpiryProcessingScheduler {

    private final GovernanceExceptionExpiryProcessingService service;

    public GovernanceExceptionExpiryProcessingScheduler(
            GovernanceExceptionExpiryProcessingService service) {
        this.service = Objects.requireNonNull(
                service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.governance.exception-expiry.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
