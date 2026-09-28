package io.wyrmgate.iam.governance.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical polling only; Governance owns RequestItem evaluation semantics. */
public final class AccessRequestEvaluationProcessingScheduler {

    private final AccessRequestEvaluationProcessingService service;

    public AccessRequestEvaluationProcessingScheduler(
            AccessRequestEvaluationProcessingService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.governance.request-evaluation.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
