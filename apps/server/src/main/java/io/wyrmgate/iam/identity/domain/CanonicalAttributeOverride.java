package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Explicit governed override. Time validity is evaluated directly and never rewrites observations. */
public record CanonicalAttributeOverride(
        UUID id,
        UUID identityId,
        UUID attributeDefinitionId,
        UUID attributeDefinitionVersionId,
        State state,
        String reason,
        Instant validFrom,
        Instant validUntil,
        long revision,
        Instant createdAt,
        Instant supersededAt,
        UUID correlationId,
        UUID causationId,
        List<CanonicalValue> values) {

    public enum State { ACTIVE, SUPERSEDED }

    public CanonicalAttributeOverride {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(attributeDefinitionId, "attributeDefinitionId");
        Objects.requireNonNull(attributeDefinitionVersionId, "attributeDefinitionVersionId");
        Objects.requireNonNull(state, "state");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        if (validUntil != null && validFrom != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(correlationId, "correlationId");
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (values.isEmpty()) throw new IllegalArgumentException("override must contain at least one value");
    }

    public boolean effectiveAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return state == State.ACTIVE
                && (validFrom == null || !instant.isBefore(validFrom))
                && (validUntil == null || instant.isBefore(validUntil));
    }
}
