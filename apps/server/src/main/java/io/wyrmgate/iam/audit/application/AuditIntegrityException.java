package io.wyrmgate.iam.audit.application;

import java.util.UUID;

public final class AuditIntegrityException extends RuntimeException {
    public AuditIntegrityException(UUID recordId) {
        super("AuditRecord integrity verification failed for " + recordId);
    }
}
