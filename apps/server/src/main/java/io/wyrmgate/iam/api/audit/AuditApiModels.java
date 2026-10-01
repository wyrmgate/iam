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
