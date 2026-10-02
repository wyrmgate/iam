package io.wyrmgate.iam.audit.domain;

/** Closed ADR-0036 explanatory display snapshot attached to an immutable AuditRecord. */
public record AuditMaterialSnapshot(
        String actorDisplayLabel,
        String resourceDisplayLabel,
        Long resourceRevision,
        String resourceState) {

    public static final String SCHEMA_VERSION = "audit-material-v1";

    public AuditMaterialSnapshot {
        actorDisplayLabel = bounded(actorDisplayLabel, "actorDisplayLabel");
        resourceDisplayLabel = bounded(resourceDisplayLabel, "resourceDisplayLabel");
        resourceState = bounded(resourceState, "resourceState");
        if (resourceRevision != null && resourceRevision < 1) {
            throw new IllegalArgumentException("resourceRevision must be positive");
        }
        if (actorDisplayLabel == null
                && resourceDisplayLabel == null
                && resourceRevision == null
                && resourceState == null) {
            throw new IllegalArgumentException("material snapshot must contain at least one value");
        }
    }

    private static String bounded(String value, String field) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException(field + " must contain between 1 and 256 characters");
        }
        return normalized;
    }
}
