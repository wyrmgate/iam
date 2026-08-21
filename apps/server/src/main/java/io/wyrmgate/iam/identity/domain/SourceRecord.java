package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current positive normalized source observation; absence requires trustworthy complete import semantics. */
public record SourceRecord(
        UUID id,
        UUID sourceSystemId,
        String nativeKey,
        String observedAttributesJson,
        Instant sourceUpdatedAt,
        Instant firstObservedAt,
        Instant lastObservedAt,
        UUID lastImportRunId,
        UUID lastCompleteImportRunId) {

    public SourceRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceSystemId, "sourceSystemId");
        if (nativeKey == null || nativeKey.isBlank()) {
            throw new IllegalArgumentException("nativeKey must not be blank");
        }
        if (observedAttributesJson == null || observedAttributesJson.isBlank()) {
            throw new IllegalArgumentException("observedAttributesJson must not be blank");
        }
        Objects.requireNonNull(firstObservedAt, "firstObservedAt");
        Objects.requireNonNull(lastObservedAt, "lastObservedAt");
        Objects.requireNonNull(lastImportRunId, "lastImportRunId");
        if (lastObservedAt.isBefore(firstObservedAt)) {
            throw new IllegalArgumentException("lastObservedAt must not precede firstObservedAt");
        }
    }
}
