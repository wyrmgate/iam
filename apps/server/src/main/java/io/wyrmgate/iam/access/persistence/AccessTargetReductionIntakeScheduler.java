package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessTargetReductionIntakeService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class AccessTargetReductionIntakeScheduler {

    private final AccessTargetReductionIntakeService service;

    public AccessTargetReductionIntakeScheduler(
            AccessTargetReductionIntakeService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.access.catalog-target-reduction-intake.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
