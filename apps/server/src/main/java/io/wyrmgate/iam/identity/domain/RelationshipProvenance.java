package io.wyrmgate.iam.identity.domain;

import java.util.Objects;
import java.util.UUID;

/** Typed provenance for first-class Identity relationships. */
public record RelationshipProvenance(
        RelationshipProvenanceKind kind,
        UUID sourceRecordId) {

    public RelationshipProvenance {
        Objects.requireNonNull(kind, "kind");
        if (kind == RelationshipProvenanceKind.MANUAL && sourceRecordId != null) {
            throw new IllegalArgumentException("MANUAL relationship provenance cannot reference a SourceRecord");
        }
        if (kind == RelationshipProvenanceKind.SOURCE_RECORD && sourceRecordId == null) {
            throw new IllegalArgumentException("SOURCE_RECORD relationship provenance requires a SourceRecord");
        }
    }

    public static RelationshipProvenance manual() {
        return new RelationshipProvenance(RelationshipProvenanceKind.MANUAL, null);
    }

    public static RelationshipProvenance sourceRecord(UUID sourceRecordId) {
        return new RelationshipProvenance(
                RelationshipProvenanceKind.SOURCE_RECORD,
                Objects.requireNonNull(sourceRecordId, "sourceRecordId"));
    }
}
