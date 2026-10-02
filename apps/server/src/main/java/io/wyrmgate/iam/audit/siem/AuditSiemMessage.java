package io.wyrmgate.iam.audit.siem;

import io.wyrmgate.iam.audit.domain.AuditIntegrityMetadata;
import io.wyrmgate.iam.audit.domain.AuditMaterialSnapshot;
import java.time.Instant;
import java.util.UUID;

/** Closed ADR-0037 SIEM v1 payload. */
public record AuditSiemMessage(
        String version,
        UUID messageId,
        UUID tenantId,
        UUID auditRecordId,
        Instant occurredAt,
        Instant recordedAt,
        UUID actorId,
        String actionType,
        String resourceType,
        UUID resourceId,
        String outcome,
        UUID correlationId,
        UUID causationId,
        AuditMaterialSnapshot materialSnapshot,
        AuditIntegrityMetadata integrityMetadata) {

    public static final String VERSION = "audit-siem-v1";
}
