package io.wyrmgate.iam.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable Audit-owned evidence for one security/governance-significant semantic action. */
public record AuditRecord(
        UUID id,
        Instant occurredAt,
        Instant recordedAt,
        UUID actorId,
        String actionType,
        String resourceType,
        UUID resourceId,
        AuditOutcome outcome,
        UUID correlationId,
        UUID causationId) {

    public AuditRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("actionType must not be blank");
        }
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("resourceType must not be blank");
        }
        actionType = actionType.trim();
        resourceType = resourceType.trim();
        if (actionType.length() > 128) {
            throw new IllegalArgumentException("actionType must contain at most 128 characters");
        }
        if (resourceType.length() > 128) {
            throw new IllegalArgumentException("resourceType must contain at most 128 characters");
        }
        Objects.requireNonNull(outcome, "outcome");
    }

    public boolean semanticallyEquals(AuditRecordDraftLike other) {
        return id.equals(other.id())
                && occurredAt.equals(other.occurredAt())
                && Objects.equals(actorId, other.actorId())
                && actionType.equals(other.actionType())
                && resourceType.equals(other.resourceType())
                && Objects.equals(resourceId, other.resourceId())
                && outcome == other.outcome()
                && Objects.equals(correlationId, other.correlationId())
                && Objects.equals(causationId, other.causationId());
    }

    /** Minimal structural view used to compare replay input without coupling domain to application packages. */
    public interface AuditRecordDraftLike {
        UUID id();
        Instant occurredAt();
        UUID actorId();
        String actionType();
        String resourceType();
        UUID resourceId();
        AuditOutcome outcome();
        UUID correlationId();
        UUID causationId();
    }
}
