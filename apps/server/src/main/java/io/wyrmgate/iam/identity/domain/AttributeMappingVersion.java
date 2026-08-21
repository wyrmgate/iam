package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Activated source-field mapping to one canonical attribute definition version. */
public record AttributeMappingVersion(
        UUID id,
        UUID sourceSystemId,
        UUID attributeDefinitionVersionId,
        long versionNumber,
        String sourcePath,
        State state,
        Instant createdAt,
        Instant activatedAt,
        Instant supersededAt) {

    public enum State { ACTIVE, SUPERSEDED }

    public AttributeMappingVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(attributeDefinitionVersionId, "attributeDefinitionVersionId");
        if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be positive");
        if (sourcePath == null || sourcePath.isBlank()) throw new IllegalArgumentException("sourcePath must not be blank");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(activatedAt, "activatedAt");
        if (state == State.ACTIVE && supersededAt != null) throw new IllegalArgumentException("active mapping must not be superseded");
        if (state == State.SUPERSEDED && (supersededAt == null || supersededAt.isBefore(activatedAt))) {
            throw new IllegalArgumentException("superseded mapping requires ordered timestamps");
        }
    }
}
