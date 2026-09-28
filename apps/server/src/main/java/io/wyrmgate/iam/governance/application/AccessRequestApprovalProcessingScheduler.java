package io.wyrmgate.iam.governance.application;

import org.springframework.scheduling.annotation.Scheduled;

public final class AccessRequestApprovalProcessingScheduler {

    private final AccessRequestApprovalProcessingService service;

    public AccessRequestApprovalProcessingScheduler(
            AccessRequestApprovalProcessingService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${iam.governance.approval-processing-delay-ms:1000}")
    public void process() {
        service.processAvailable();
    }
}
