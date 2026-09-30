package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditRecord.AuditRecordDraftLike;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Producer-supplied semantic content for one stable AuditRecord ID. */
public record AuditRecordDraft(
        UUID id,
        Instant occurredAt,
        UUID actorId,
        String actionType,
        String resourceType,
        UUID resourceId,
        AuditOutcome outcome,
        UUID correlationId,
        UUID causationId) implements AuditRecordDraftLike {

    public AuditRecordDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        actionType = boundedType(actionType, "actionType");
        resourceType = boundedType(resourceType, "resourceType");
        if (actionType.length() > 128) {
            throw new IllegalArgumentException("actionType must contain at most 128 characters");
        }
        if (resourceType.length() > 128) {
            throw new IllegalArgumentException("resourceType must contain at most 128 characters");
        }
        Objects.requireNonNull(outcome, "outcome");
    }

    private static String boundedType(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException(field + " must not exceed 128 characters");
        }
        return normalized;
    }
}
