package io.wyrmgate.iam.api.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class AuditApiModels {
    private AuditApiModels() {
    }

    record AuditRecordResource(
            UUID id,
            Instant occurredAt,
            Instant recordedAt,
            UUID actorId,
            String actionType,
            String resourceType,
            UUID resourceId,
            String outcome,
            UUID correlationId,
            UUID causationId) {
    }

    record AuditRecordPage(
            List<AuditRecordResource> items,
            String nextCursor) {
    }

    record AuditExportCreateRequest(
            UUID actorId,
            String actionType,
            String resourceType,
            UUID resourceId,
            String outcome,
            UUID correlationId,
            Instant occurredFrom,
            Instant occurredUntil) {
    }

    record AuditExportResource(
            UUID id,
            UUID requestedByIdentityId,
            UUID actorId,
            String actionType,
            String resourceType,
            UUID resourceId,
            String outcome,
            UUID correlationId,
            Instant occurredFrom,
            Instant occurredUntil,
            Instant snapshotRecordedAt,
            String schemaVersion,
            String state,
            long recordCount,
            long byteCount,
            String sha256,
            Instant artifactExpiresAt,
            String failureCode,
            long revision,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    record EvidenceReferenceResource(
            String resourceType,
            UUID resourceId,
            Long revision,
            String displayLabel) {
    }

    record EvidenceSnapshotResource(
            UUID id,
            Instant occurredAt,
            Instant recordedAt,
            UUID actorId,
            String snapshotType,
            EvidenceReferenceResource subject,
            EvidenceReferenceResource policy,
            EvidenceReferenceResource related,
            String decisionLabel,
            UUID correlationId,
            UUID causationId) {
    }

    record EvidenceSnapshotPage(
            List<EvidenceSnapshotResource> items,
            String nextCursor) {
    }

    record FieldError(
            String field,
            String code,
            String message) {
    }

    record ErrorResponse(
            String code,
            String message,
            UUID correlationId,
            List<FieldError> fieldErrors) {
    }
}
