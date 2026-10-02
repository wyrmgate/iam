package io.wyrmgate.iam.audit.application;

public interface AuditSiemPublisher {
    void publish(AuditSiemMessage message);
}
