package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable activated policy governing automatic SourceRecord-to-Identity correlation. */
public record SourceCorrelationPolicyVersion(
        UUID id,
        UUID sourceSystemId,
        UUID matchAttributeDefinitionVersionId,
        UUID matchMappingVersionId,
        long versionNumber,
        boolean createIdentityOnNoMatch,
        IdentityType createdIdentityType,
        String displayNameSourcePath,
        State state,
        Instant createdAt,
        Instant activatedAt,
        Instant supersededAt) {

    public enum State {
        ACTIVE,
        SUPERSEDED
    }

    public SourceCorrelationPolicyVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(matchAttributeDefinitionVersionId, "matchAttributeDefinitionVersionId");
        Objects.requireNonNull(matchMappingVersionId, "matchMappingVersionId");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(activatedAt, "activatedAt");
        if (createIdentityOnNoMatch) {
            Objects.requireNonNull(createdIdentityType, "createdIdentityType");
            if (displayNameSourcePath == null || displayNameSourcePath.isBlank()) {
                throw new IllegalArgumentException("displayNameSourcePath is required when creation is enabled");
            }
        } else if (createdIdentityType != null || displayNameSourcePath != null) {
            throw new IllegalArgumentException("creation metadata must be absent when creation is disabled");
        }
        if (state == State.ACTIVE && supersededAt != null) {
            throw new IllegalArgumentException("active policy must not be superseded");
        }
        if (state == State.SUPERSEDED
                && (supersededAt == null || supersededAt.isBefore(activatedAt))) {
            throw new IllegalArgumentException("superseded policy requires ordered timestamps");
        }
    }
}
