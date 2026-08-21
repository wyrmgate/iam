package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current positive source observation; absence is inferred only from trustworthy complete import semantics. */
public record SourceRecord(
        UUID id,
        UUID sourceSystemId,
        String nativeKey,
        String nativePayloadJson,
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
        if (nativePayloadJson == null || nativePayloadJson.isBlank()) {
            throw new IllegalArgumentException("nativePayloadJson must not be blank");
        }
        Objects.requireNonNull(firstObservedAt, "firstObservedAt");
        Objects.requireNonNull(lastObservedAt, "lastObservedAt");
        Objects.requireNonNull(lastImportRunId, "lastImportRunId");
        if (lastObservedAt.isBefore(firstObservedAt)) {
            throw new IllegalArgumentException("lastObservedAt must not precede firstObservedAt");
        }
    }
}
