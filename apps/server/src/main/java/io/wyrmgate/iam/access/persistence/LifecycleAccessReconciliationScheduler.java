package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.LifecycleAccessReconciliationService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class LifecycleAccessReconciliationScheduler {

    private final LifecycleAccessReconciliationService service;

    public LifecycleAccessReconciliationScheduler(
            LifecycleAccessReconciliationService service) {
        this.service=Objects.requireNonNull(service);
    }

    @Scheduled(fixedDelayString = "${iam.access.lifecycle-policy.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
