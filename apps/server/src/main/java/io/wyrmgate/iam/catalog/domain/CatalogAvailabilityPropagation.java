package io.wyrmgate.iam.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Catalog-owned durable bounded propagation of one material availability/expansion cause. */
public record CatalogAvailabilityPropagation(
        UUID id,
        CatalogAvailabilityCauseKind causeKind,
        UUID causeId,
        long causeRevision,
        Phase phase,
        Instant afterCreatedAt,
        UUID afterResourceId,
        long processedResourceCount,
        long revision,
        State state,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public CatalogAvailabilityPropagation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(causeKind, "causeKind");
        Objects.requireNonNull(causeId, "causeId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (causeRevision < 1 || revision < 1) {
            throw new IllegalArgumentException("revisions must be positive");
        }
        if ((afterCreatedAt == null) != (afterResourceId == null)) {
            throw new IllegalArgumentException(
                    "propagation checkpoint timestamp and resource ID must be both present or both absent");
        }
        if (processedResourceCount < 0) {
            throw new IllegalArgumentException("processedResourceCount must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (state == State.RUNNING && completedAt != null) {
            throw new IllegalArgumentException("RUNNING propagation must not have completedAt");
        }
        if (state == State.COMPLETED && completedAt == null) {
            throw new IllegalArgumentException("COMPLETED propagation requires completedAt");
        }
    }

    public enum Phase {
        ENTITLEMENTS,
        ROLES,
        ROLE_EXPANSIONS
    }

    public enum State {
        RUNNING,
        COMPLETED
    }
}
