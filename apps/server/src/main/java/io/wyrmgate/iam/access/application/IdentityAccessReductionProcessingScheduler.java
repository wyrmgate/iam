package io.wyrmgate.iam.access.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class IdentityAccessReductionProcessingScheduler {

    private final IdentityAccessReductionProcessingService service;

    public IdentityAccessReductionProcessingScheduler(
            IdentityAccessReductionProcessingService service) {
        this.service = Objects.requireNonNull(
                service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.access.identity-reduction.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
