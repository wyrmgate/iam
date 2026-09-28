package io.wyrmgate.iam.governance.application;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical polling only; Governance and Access retain their domain state. */
public final class AccessRequestAccessApplicationScheduler {

    private final AccessRequestAccessApplicationService service;

    public AccessRequestAccessApplicationScheduler(
            AccessRequestAccessApplicationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.governance.access-application.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
