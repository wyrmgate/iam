package io.wyrmgate.iam.integration.persistence;

import io.wyrmgate.iam.integration.application.GrantProvisioningPlanningProcessor;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class GrantProvisioningPlanningScheduler {

    private final GrantProvisioningPlanningProcessor processor;

    public GrantProvisioningPlanningScheduler(
            GrantProvisioningPlanningProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    @Scheduled(fixedDelayString = "${iam.integration.provisioning-plan.poll-interval:PT1S}")
    public void processAvailable() {
        processor.processAvailable();
    }
}
