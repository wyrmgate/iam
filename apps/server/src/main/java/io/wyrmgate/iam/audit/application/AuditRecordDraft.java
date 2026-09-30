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
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("actionType must not be blank");
        }
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType must not be blank");
        }
        actionType = actionType.trim();
        resourceType = resourceType.trim();
        Objects.requireNonNull(outcome, "outcome");
    }
}
