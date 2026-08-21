package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Authoritative resolved canonical attribute state. */
public record CanonicalAttributeState(
        UUID id,
        UUID identityId,
        UUID attributeDefinitionId,
        UUID attributeDefinitionVersionId,
        ResolutionStatus resolutionStatus,
        UUID selectedCandidateId,
        UUID authorityRuleVersionId,
        long valueRevision,
        Instant createdAt,
        Instant updatedAt,
        List<CanonicalValue> values) {

    public enum ResolutionStatus { RESOLVED, OVERRIDDEN, CONFLICT, UNRESOLVED, NO_VALUE }

    public CanonicalAttributeState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(attributeDefinitionId, "attributeDefinitionId");
        Objects.requireNonNull(attributeDefinitionVersionId, "attributeDefinitionVersionId");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        if (valueRevision < 1) throw new IllegalArgumentException("valueRevision must be positive");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        if ((resolutionStatus == ResolutionStatus.RESOLVED || resolutionStatus == ResolutionStatus.OVERRIDDEN)
                && values.isEmpty()) {
            throw new IllegalArgumentException("resolved/overridden state requires values");
        }
    }
}
