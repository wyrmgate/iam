package io.wyrmgate.iam.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Audit-owned durable archive generation state; terminal segments are immutable evidence. */
public record AuditArchiveSegment(
        UUID id,
        long retentionPolicyVersion,
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
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final String NDJSON_V1 = "audit-archive-ndjson-v1";
    public static final String CONTENT_TYPE = "application/x-ndjson";

    public enum State {
        REQUESTED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public AuditArchiveSegment {
        Objects.requireNonNull(id, "id");
        if (retentionPolicyVersion < 1) {
            throw new IllegalArgumentException("retentionPolicyVersion must be positive");
        }
        Objects.requireNonNull(occurredFrom, "occurredFrom");
        Objects.requireNonNull(occurredUntil, "occurredUntil");
        Objects.requireNonNull(snapshotRecordedAt, "snapshotRecordedAt");
        if (!occurredUntil.isAfter(occurredFrom)) {
            throw new IllegalArgumentException("occurredUntil must be after occurredFrom");
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion must not be blank");
        }
        Objects.requireNonNull(state, "state");
        if ((continuationOccurredAt == null) != (continuationId == null)) {
            throw new IllegalArgumentException("continuation values must be both null or both present");
        }
        if (recordCount < 0 || byteCount < 0) {
            throw new IllegalArgumentException("archive counts must be non-negative");
        }
        if (sha256Hex != null && !sha256Hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256Hex must be lowercase SHA-256");
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (state == State.SUCCEEDED
                && (artifactReference == null || sha256Hex == null || completedAt == null || failureCode != null)) {
            throw new IllegalArgumentException("SUCCEEDED archive requires verified artifact metadata");
        }
        if (state == State.FAILED
                && (failureCode == null || completedAt == null || artifactReference != null || sha256Hex != null)) {
            throw new IllegalArgumentException("FAILED archive requires failure evidence and no committed artifact metadata");
        }
        if ((state == State.REQUESTED || state == State.RUNNING)
                && (completedAt != null || failureCode != null || artifactReference != null || sha256Hex != null)) {
            throw new IllegalArgumentException("non-terminal archive must not have terminal artifact evidence");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
