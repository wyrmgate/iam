package io.wyrmgate.iam.governance.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical trigger only; Governance retains finding semantics in the processing service. */
public final class GovernanceObservationProcessingScheduler {

    private final GovernanceObservationProcessingService service;

    public GovernanceObservationProcessingScheduler(
            GovernanceObservationProcessingService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.governance.observation-refresh.poll-interval:PT1M}")
    public void refreshDue(){service.refreshDue();}

    @Scheduled(fixedDelayString = "${iam.governance.observation-processing.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
