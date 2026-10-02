package io.wyrmgate.iam.audit.archive;

import io.wyrmgate.iam.audit.application.AuditEvidenceLifecycleService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical poll trigger only; Audit owns legal-hold and purge semantics. */
final class AuditPurgeScheduler {

    private final AuditEvidenceLifecycleService service;

    AuditPurgeScheduler(AuditEvidenceLifecycleService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.audit.purge.poll-interval:PT5S}")
    void executeAvailable() {
        service.executeAvailable();
    }
}
