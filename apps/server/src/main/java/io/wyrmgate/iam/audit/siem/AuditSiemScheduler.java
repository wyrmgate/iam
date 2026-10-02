package io.wyrmgate.iam.audit.siem;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

final class AuditSiemScheduler {
    private final AuditSiemDeliveryService service;

    AuditSiemScheduler(AuditSiemDeliveryService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${iam.audit.siem.poll-interval:PT1S}")
    void deliverAvailable() {
        service.deliverAvailable();
    }
}
