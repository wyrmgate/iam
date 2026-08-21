package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SourceImportRun(
        UUID id,
        UUID sourceSystemId,
        SourceImportRunState state,
        SourceImportCompleteness completeness,
        Instant startedAt,
        Instant completedAt,
        String checkpointToken,
        String partialReason) {

    public SourceImportRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(completeness, "completeness");
        Objects.requireNonNull(startedAt, "startedAt");
        if (state == SourceImportRunState.RUNNING && completedAt != null) {
            throw new IllegalArgumentException("running import must not have completedAt");
        }
        if (state != SourceImportRunState.RUNNING && completedAt == null) {
            throw new IllegalArgumentException("finished import must have completedAt");
        }
        if (completedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not precede startedAt");
        }
        if (completeness == SourceImportCompleteness.PARTIAL
                && (partialReason == null || partialReason.isBlank())) {
            throw new IllegalArgumentException("partial import requires a reason");
        }
    }
}
