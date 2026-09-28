package io.wyrmgate.iam.governance.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class AccessRequestApprovalOutcomeProcessingScheduler {

    private final AccessRequestApprovalOutcomeProcessingService service;

    public AccessRequestApprovalOutcomeProcessingScheduler(
            AccessRequestApprovalOutcomeProcessingService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.governance.approval-outcome.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
