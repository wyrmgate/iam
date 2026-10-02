package io.wyrmgate.iam.audit.siem;

@FunctionalInterface
public interface AuditSiemPublisher {
    void publish(AuditSiemMessage message);
}
