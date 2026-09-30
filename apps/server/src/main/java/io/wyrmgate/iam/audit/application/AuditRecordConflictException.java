package io.wyrmgate.iam.audit.application;

import java.util.UUID;

public final class AuditRecordConflictException extends RuntimeException {
    public AuditRecordConflictException(UUID recordId) {
        super("audit record id was reused with different semantic content: " + recordId);
    }
}
