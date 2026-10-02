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
        UUID causationId,
        AuditMaterialSnapshot materialSnapshot,
        AuditIntegrityMetadata integrityMetadata) {

    public AuditRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        actionType = boundedType(actionType, "actionType");
        resourceType = boundedType(resourceType, "resourceType");
        Objects.requireNonNull(outcome, "outcome");
    }

    public AuditRecord(
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
        this(
                id,
                occurredAt,
                recordedAt,
                actorId,
                actionType,
                resourceType,
                resourceId,
                outcome,
                correlationId,
                causationId,
                null,
                null);
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

    public boolean semanticallyEquals(AuditRecordDraftLike other) {
        return id.equals(other.id())
                && occurredAt.equals(other.occurredAt())
                && Objects.equals(actorId, other.actorId())
                && actionType.equals(other.actionType())
                && resourceType.equals(other.resourceType())
                && Objects.equals(resourceId, other.resourceId())
                && outcome == other.outcome()
                && Objects.equals(correlationId, other.correlationId())
                && Objects.equals(causationId, other.causationId())
                && Objects.equals(materialSnapshot, other.materialSnapshot());
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

        default AuditMaterialSnapshot materialSnapshot() {
            return null;
        }
    }
}
