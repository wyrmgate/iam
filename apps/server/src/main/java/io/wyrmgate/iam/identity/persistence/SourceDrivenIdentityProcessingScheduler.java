package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.identity.application.SourceDrivenIdentityProcessingService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical polling trigger for ADR-0022 positive source processing. */
public final class SourceDrivenIdentityProcessingScheduler {

    private final SourceDrivenIdentityProcessingService service;

    public SourceDrivenIdentityProcessingScheduler(SourceDrivenIdentityProcessingService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.identity.source-processing.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
