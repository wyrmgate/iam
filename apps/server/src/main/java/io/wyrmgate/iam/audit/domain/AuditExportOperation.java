package io.wyrmgate.iam.audit.domain;

import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditFilter;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable Audit-owned ADR-0034 export process state. */
public record AuditExportOperation(
        UUID id,
        UUID requestedByIdentityId,
        AuditFilter filter,
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
        Instant artifactExpiresAt,
        String failureCode,
        long revision,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final String NDJSON_V1 = "audit-record-ndjson-v1";

    public enum State {
        REQUESTED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public AuditExportOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requestedByIdentityId, "requestedByIdentityId");
        Objects.requireNonNull(filter, "filter");
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
        if (recordCount < 0 || byteCount < 0) throw new IllegalArgumentException("export counts must be non-negative");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (state == State.SUCCEEDED
                && (artifactReference == null || sha256Hex == null || completedAt == null || failureCode != null)) {
            throw new IllegalArgumentException("SUCCEEDED export requires artifact metadata and completion");
        }
        if (state == State.FAILED
                && (failureCode == null || completedAt == null || artifactReference != null)) {
            throw new IllegalArgumentException("FAILED export requires failure evidence and no artifact reference");
        }
        if ((state == State.REQUESTED || state == State.RUNNING)
                && (completedAt != null || failureCode != null)) {
            throw new IllegalArgumentException("non-terminal export must not have terminal evidence");
        }
    }

    public boolean artifactExpiredAt(Instant now) {
        return artifactExpiresAt != null && !artifactExpiresAt.isAfter(now);
    }
}
