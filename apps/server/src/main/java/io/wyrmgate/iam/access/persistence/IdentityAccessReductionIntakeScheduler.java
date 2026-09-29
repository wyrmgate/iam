package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.IdentityAccessReductionIntakeService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class IdentityAccessReductionIntakeScheduler {

    private final IdentityAccessReductionIntakeService service;

    public IdentityAccessReductionIntakeScheduler(
            IdentityAccessReductionIntakeService service) {
        this.service = Objects.requireNonNull(
                service, "service");
    }

    @Scheduled(
            fixedDelayString =
                    "${iam.access.identity-reduction-intake.poll-interval:PT1S}")
    public void processAvailable() {
        service.processAvailable();
    }
}
