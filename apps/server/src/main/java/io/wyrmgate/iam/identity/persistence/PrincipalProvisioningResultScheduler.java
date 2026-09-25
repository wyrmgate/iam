package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.PrincipalProvisioningResultProcessor;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class PrincipalProvisioningResultScheduler {

    private final PrincipalProvisioningResultProcessor processor;

    public PrincipalProvisioningResultScheduler(
            PrincipalProvisioningResultProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    @Scheduled(fixedDelayString = "${iam.identity.principal-provisioning-result.poll-interval:PT1S}")
    public void processAvailable() {
        processor.processAvailable();
    }
}
