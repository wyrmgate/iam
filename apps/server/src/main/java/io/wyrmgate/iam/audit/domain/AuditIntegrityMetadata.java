package io.wyrmgate.iam.audit.domain;

import java.util.Objects;

/** Derived ADR-0036 integrity metadata for immutable AuditRecord content. */
public record AuditIntegrityMetadata(
        String contentSha256,
        String materialSnapshotSha256) {

    public static final String SCHEMA_VERSION = "audit-integrity-v1";
    public static final String ALGORITHM = "SHA-256";

    public AuditIntegrityMetadata {
        contentSha256 = digest(contentSha256, "contentSha256");
        if (materialSnapshotSha256 != null) {
            materialSnapshotSha256 = digest(materialSnapshotSha256, "materialSnapshotSha256");
        }
    }

    private static String digest(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
        }
        return value;
    }
}
