package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Normalized source-derived candidate with explicit source and mapping provenance. */
public record CanonicalAttributeCandidate(
        UUID id,
        UUID identityId,
        UUID attributeDefinitionVersionId,
        UUID sourceSystemId,
        UUID sourceRecordId,
        UUID mappingVersionId,
        String sourcePath,
        long candidateRevision,
        Instant sourceUpdatedAt,
        Instant observedAt,
        Instant updatedAt,
        List<CanonicalValue> values) {

    public CanonicalAttributeCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(attributeDefinitionVersionId, "attributeDefinitionVersionId");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        Objects.requireNonNull(mappingVersionId, "mappingVersionId");
        if (sourcePath == null || sourcePath.isBlank()) throw new IllegalArgumentException("sourcePath must not be blank");
        if (candidateRevision < 1) throw new IllegalArgumentException("candidateRevision must be positive");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(observedAt)) throw new IllegalArgumentException("updatedAt must not precede observedAt");
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (values.isEmpty()) throw new IllegalArgumentException("candidate must contain at least one value");
    }
}
