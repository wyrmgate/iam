package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned durable process for bounded destructive absence inference after a trusted COMPLETE import. */
public record SourceAbsenceInference(
        UUID id,
        UUID sourceSystemId,
        UUID importRunId,
        UUID policyVersionId,
        int maxInferredTransitions,
        State state,
        Instant afterFirstObservedAt,
        UUID afterSourceRecordId,
        long processedCandidateCount,
        long inferredTransitionCount,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public enum State {
        RUNNING,
        COMPLETED,
        SUPERSEDED,
        MANUAL_REQUIRED
    }

    public SourceAbsenceInference {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(importRunId, "importRunId");
        Objects.requireNonNull(policyVersionId, "policyVersionId");
        if (maxInferredTransitions < 1) {
            throw new IllegalArgumentException("maxInferredTransitions must be positive");
        }
        Objects.requireNonNull(state, "state");
        if ((afterFirstObservedAt == null) != (afterSourceRecordId == null)) {
            throw new IllegalArgumentException("absence checkpoint fields must be both present or both absent");
        }
        if (processedCandidateCount < 0 || inferredTransitionCount < 0) {
            throw new IllegalArgumentException("absence counters must not be negative");
        }
        if (inferredTransitionCount > maxInferredTransitions) {
            throw new IllegalArgumentException("absence transition count exceeds policy ceiling");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if (state == State.RUNNING && completedAt != null) {
            throw new IllegalArgumentException("RUNNING inference must not be completed");
        }
        if (state != State.RUNNING && completedAt == null) {
            throw new IllegalArgumentException("terminal inference requires completedAt");
        }
    }
}
