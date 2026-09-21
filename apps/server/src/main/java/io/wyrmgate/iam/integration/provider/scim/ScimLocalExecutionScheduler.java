package io.wyrmgate.iam.integration.provider.scim;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

final class ScimLocalExecutionScheduler {

    private final ScimLocalExecutionService service;

    ScimLocalExecutionScheduler(ScimLocalExecutionService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.integration.scim-local.poll-interval:PT1S}")
    void executeAvailable() {
        service.executeAvailable();
    }
}
