package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditIntegrityMetadata;
import io.wyrmgate.iam.audit.domain.AuditMaterialSnapshot;
import java.time.Instant;
import java.util.UUID;

/** Closed, data-minimized ADR-0037 SIEM v1 message. */
public record AuditSiemMessage(
        String schemaVersion,
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

    public static final String SCHEMA_VERSION = "audit-siem-v1";
}
