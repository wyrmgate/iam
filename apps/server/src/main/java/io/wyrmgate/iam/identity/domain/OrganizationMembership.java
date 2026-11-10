package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** First-class temporal membership of a governed Identity in an Organization. */
public record OrganizationMembership(
        UUID id,
        UUID subjectIdentityId,
        UUID organizationId,
        Instant validFrom,
        Instant validUntil,
        RelationshipProvenance provenance,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public OrganizationMembership {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subjectIdentityId, "subjectIdentityId");
        Objects.requireNonNull(organizationId, "organizationId");
        validateTime(validFrom, validUntil, createdAt, updatedAt);
        Objects.requireNonNull(provenance, "provenance");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }

    public boolean effectiveAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil));
    }

    private static void validateTime(
            Instant validFrom, Instant validUntil, Instant createdAt, Instant updatedAt) {
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
