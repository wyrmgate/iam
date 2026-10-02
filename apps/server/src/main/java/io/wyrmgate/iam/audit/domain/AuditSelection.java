package io.wyrmgate.iam.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Exact bounded AuditRecord selection used by holds, purge and evidence lifecycle work. */
public record AuditSelection(
        Instant occurredFrom,
        Instant occurredUntil,
        UUID actorId,
        String actionType,
        String resourceType,
        UUID resourceId,
        AuditOutcome outcome,
        UUID correlationId) {

    public AuditSelection {
        Objects.requireNonNull(occurredFrom, "occurredFrom");
        Objects.requireNonNull(occurredUntil, "occurredUntil");
        if (!occurredUntil.isAfter(occurredFrom)) {
            throw new IllegalArgumentException("occurredUntil must be after occurredFrom");
        }
        actionType = normalized(actionType, "actionType");
        resourceType = normalized(resourceType, "resourceType");
    }

    private static String normalized(String value, String field) {
        if (value == null) return null;
        String result = value.trim();
        if (result.isEmpty() || result.length() > 128) {
            throw new IllegalArgumentException(field + " must contain between 1 and 128 characters");
        }
        return result;
    }
}
