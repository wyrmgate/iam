package io.wyrmgate.iam.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable ADR-0034 immutable archive-segment generation state. */
public record AuditArchiveSegment(
        UUID id,
        UUID retentionPolicyVersionId,
        Instant occurredFrom,
        Instant occurredUntil,
        Instant snapshotRecordedAt,
        String schemaVersion,
        State state,
        Instant continuationOccurredAt,
        UUID continuationId,
        long recordCount,
        long byteCount,
        String sha256Hex,
        String artifactReference,
        String failureCode,
        UUID correlationId,
        UUID causationId,
        long revision,
        Instant verifiedAt,
        Instant minimumRetainUntil,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final String NDJSON_V1 = "audit-archive-ndjson-v1";
    public static final String NDJSON_V2 = "audit-archive-ndjson-v2";

    public enum State {
        REQUESTED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public AuditArchiveSegment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(retentionPolicyVersionId, "retentionPolicyVersionId");
        Objects.requireNonNull(occurredFrom, "occurredFrom");
        Objects.requireNonNull(occurredUntil, "occurredUntil");
        Objects.requireNonNull(snapshotRecordedAt, "snapshotRecordedAt");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!occurredUntil.isAfter(occurredFrom)) {
            throw new IllegalArgumentException("occurredUntil must be after occurredFrom");
        }
        if (schemaVersion.isBlank()) throw new IllegalArgumentException("schemaVersion must not be blank");
        if ((continuationOccurredAt == null) != (continuationId == null)) {
            throw new IllegalArgumentException("continuation values must be both null or both present");
        }
        if (recordCount < 0 || byteCount < 0) {
            throw new IllegalArgumentException("archive counts must be non-negative");
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (state == State.SUCCEEDED
                && (artifactReference == null || sha256Hex == null || completedAt == null
                    || verifiedAt == null || minimumRetainUntil == null || failureCode != null)) {
            throw new IllegalArgumentException("SUCCEEDED archive requires verified artifact metadata");
        }
        if (state == State.FAILED
                && (failureCode == null || completedAt == null || artifactReference != null
                    || verifiedAt != null || minimumRetainUntil != null)) {
            throw new IllegalArgumentException("FAILED archive requires failure evidence only");
        }
        if ((state == State.REQUESTED || state == State.RUNNING)
                && (completedAt != null || verifiedAt != null || minimumRetainUntil != null || failureCode != null)) {
            throw new IllegalArgumentException("non-terminal archive must not have terminal evidence");
        }
    }
}
