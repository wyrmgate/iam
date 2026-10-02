package io.wyrmgate.iam.audit.domain;

import java.util.Objects;
import java.util.UUID;

/** Stable bounded resource reference captured in an EvidenceSnapshot. */
public record EvidenceResourceReference(
        String resourceType,
        UUID resourceId,
        Long revision,
        String displayLabel) {

    public EvidenceResourceReference {
        resourceType = bounded(resourceType, 128, "resourceType", false);
        Objects.requireNonNull(resourceId, "resourceId");
        if (revision != null && revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        displayLabel = bounded(displayLabel, 256, "displayLabel", true);
    }

    private static String bounded(String value, int max, String field, boolean nullable) {
        if (value == null) {
            if (nullable) return null;
            throw new IllegalArgumentException(field + " must not be null");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException(field + " has invalid length");
        }
        return normalized;
    }
}
