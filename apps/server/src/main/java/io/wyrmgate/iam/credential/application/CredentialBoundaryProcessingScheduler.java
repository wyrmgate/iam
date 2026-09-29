package io.wyrmgate.iam.credential.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class CredentialBoundaryProcessingScheduler {

    private final CredentialBoundaryProcessingService service;

    public CredentialBoundaryProcessingScheduler(
            CredentialBoundaryProcessingService service) {
        this.service = Objects.requireNonNull(
                service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.credential.boundary.poll-interval:PT1S}")
    public void processDue() {
        service.processDue();
    }
}
