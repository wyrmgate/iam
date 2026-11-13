package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.EffectiveAccessProcessingService;
import io.wyrmgate.iam.access.application.RoleExpansionProjectionRefreshService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class EffectiveAccessProcessingScheduler {

    private final EffectiveAccessProcessingService service;
    private final RoleExpansionProjectionRefreshService roleRefresh;

    public EffectiveAccessProcessingScheduler(
            EffectiveAccessProcessingService service,
            RoleExpansionProjectionRefreshService roleRefresh) {
        this.service = Objects.requireNonNull(service, "service");
        this.roleRefresh = Objects.requireNonNull(roleRefresh, "roleRefresh");
    }

    @Scheduled(fixedDelayString = "${iam.access.effective-access.poll-interval:PT1S}")
    public void processAvailable() {
        roleRefresh.processAvailable();
        service.processAvailable();
    }
}
