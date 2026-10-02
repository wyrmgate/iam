package io.wyrmgate.iam.audit.export;

import io.wyrmgate.iam.audit.application.AuditExportService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Technical poll trigger only; Audit retains export process semantics. */
final class AuditExportScheduler {

    private final AuditExportService service;

    AuditExportScheduler(AuditExportService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.audit.export.poll-interval:PT1S}")
    void executeAvailable() {
        service.executeAvailable();
    }
}
