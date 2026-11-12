package io.wyrmgate.iam.access.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Access-owned durable process for authoritative reduction after terminal Catalog target loss. */
public record AccessTargetReduction(
        UUID id,
        AccessAssignment.TargetKind targetKind,
        UUID targetId,
        CauseKind sourceCauseKind,
        UUID sourceCauseId,
        long sourceCauseRevision,
        State state,
        Instant afterCreatedAt,
        UUID afterAssignmentId,
        long processedAssignmentCount,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public AccessTargetReduction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(sourceCauseKind, "sourceCauseKind");
        Objects.requireNonNull(sourceCauseId, "sourceCauseId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (sourceCauseRevision < 1 || revision < 1) {
            throw new IllegalArgumentException("revisions must be positive");
        }
        if ((afterCreatedAt == null) != (afterAssignmentId == null)) {
            throw new IllegalArgumentException(
                    "reduction checkpoint timestamp and assignment ID must be both present or both absent");
        }
        if (processedAssignmentCount < 0) {
            throw new IllegalArgumentException("processedAssignmentCount must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (state == State.RUNNING && completedAt != null) {
            throw new IllegalArgumentException("RUNNING reduction must not have completedAt");
        }
        if (state == State.COMPLETED && completedAt == null) {
            throw new IllegalArgumentException("COMPLETED reduction requires completedAt");
        }
    }

    public enum CauseKind {
        APPLICATION,
        APPLICATION_TARGET,
        ENTITLEMENT,
        ROLE
    }

    public enum State {
        RUNNING,
        COMPLETED
    }
}
