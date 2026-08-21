package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Activated authority ranking for one source and canonical definition version. Lower priority wins. */
public record AttributeAuthorityRuleVersion(
        UUID id,
        UUID attributeDefinitionVersionId,
        UUID sourceSystemId,
        long versionNumber,
        int priority,
        State state,
        Instant createdAt,
        Instant activatedAt,
        Instant supersededAt) {

    public enum State { ACTIVE, SUPERSEDED }

    public AttributeAuthorityRuleVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(attributeDefinitionVersionId, "attributeDefinitionVersionId");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be positive");
        if (priority < 0) throw new IllegalArgumentException("priority must not be negative");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(activatedAt, "activatedAt");
        if (state == State.ACTIVE && supersededAt != null) throw new IllegalArgumentException("active authority rule must not be superseded");
        if (state == State.SUPERSEDED && (supersededAt == null || supersededAt.isBefore(activatedAt))) {
            throw new IllegalArgumentException("superseded authority rule requires ordered timestamps");
        }
    }
}
