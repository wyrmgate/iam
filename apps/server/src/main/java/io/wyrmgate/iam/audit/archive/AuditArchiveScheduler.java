package io.wyrmgate.iam.audit.archive;

import io.wyrmgate.iam.audit.application.AuditArchiveService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical poll trigger only; Audit retains archive and retention semantics. */
final class AuditArchiveScheduler {

    private final AuditArchiveService service;

    AuditArchiveScheduler(AuditArchiveService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.audit.archive.poll-interval:PT1S}")
    void executeAvailable() {
        service.executeAvailable();
    }
}
