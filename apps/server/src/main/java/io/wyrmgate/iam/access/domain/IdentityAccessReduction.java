package io.wyrmgate.iam.access.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Access-owned durable process for authoritative access reduction after Identity lifecycle loss. */
public record IdentityAccessReduction(
        UUID id,
        UUID identityId,
        long sourceIdentityRevision,
        String sourceLifecycleState,
        State state,
        Instant snapshotAt,
        Instant afterCreatedAt,
        UUID afterAssignmentId,
        long processedAssignmentCount,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public IdentityAccessReduction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identityId, "identityId");
        requireText(sourceLifecycleState, "sourceLifecycleState");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(snapshotAt, "snapshotAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (sourceIdentityRevision < 1) {
            throw new IllegalArgumentException(
                    "sourceIdentityRevision must be positive");
        }
        if ("ACTIVE".equals(sourceLifecycleState)) {
            throw new IllegalArgumentException(
                    "IdentityAccessReduction requires an access-ineligible lifecycle state");
        }
        if ((afterCreatedAt == null) != (afterAssignmentId == null)) {
            throw new IllegalArgumentException(
                    "reduction checkpoint timestamp and assignment ID must be both present or both absent");
        }
        if (processedAssignmentCount < 0) {
            throw new IllegalArgumentException(
                    "processedAssignmentCount must not be negative");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "updatedAt must not be before createdAt");
        }
        if (state == State.RUNNING && completedAt != null) {
            throw new IllegalArgumentException(
                    "RUNNING reduction must not have completedAt");
        }
        if (state == State.COMPLETED && completedAt == null) {
            throw new IllegalArgumentException(
                    "COMPLETED reduction requires completedAt");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must not be blank");
        }
    }

    public enum State {
        RUNNING,
        COMPLETED
    }
}
