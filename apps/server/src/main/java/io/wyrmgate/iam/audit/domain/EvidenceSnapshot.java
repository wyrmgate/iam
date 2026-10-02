package io.wyrmgate.iam.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable ADR-0036 decision-time contextual reference snapshot. */
public record EvidenceSnapshot(
        UUID id,
        Instant occurredAt,
        Instant recordedAt,
        UUID actorId,
        String snapshotType,
        EvidenceResourceReference subject,
        EvidenceResourceReference policy,
        EvidenceResourceReference related,
        String decisionLabel,
        UUID correlationId,
        UUID causationId) {

    public EvidenceSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        snapshotType = bounded(snapshotType, 64, "snapshotType");
        Objects.requireNonNull(subject, "subject");
        decisionLabel = bounded(decisionLabel, 64, "decisionLabel");
    }

    private static String bounded(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }
}
