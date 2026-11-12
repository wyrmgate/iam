package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessTargetReductionProcessingService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class AccessTargetReductionProcessingScheduler {

    private final AccessTargetReductionProcessingService service;

    public AccessTargetReductionProcessingScheduler(
            AccessTargetReductionProcessingService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.access.catalog-target-reduction.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
