package io.wyrmgate.iam.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable dual-control ADR-0035 destructive Audit purge process. */
public record AuditPurgeOperation(
        UUID id,
        UUID retentionPolicyVersionId,
        UUID archiveSegmentId,
        UUID requestedByIdentityId,
        UUID approvedByIdentityId,
        AuditSelection selection,
        Instant snapshotRecordedAt,
        String reasonCode,
        State state,
        long deletedRecordCount,
        String failureCode,
        UUID correlationId,
        UUID causationId,
        long revision,
        Instant approvedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public enum State {
        REQUESTED,
        APPROVED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        BLOCKED
    }

    public AuditPurgeOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(retentionPolicyVersionId, "retentionPolicyVersionId");
        Objects.requireNonNull(archiveSegmentId, "archiveSegmentId");
        Objects.requireNonNull(requestedByIdentityId, "requestedByIdentityId");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(snapshotRecordedAt, "snapshotRecordedAt");
        if (reasonCode == null || reasonCode.isBlank() || reasonCode.trim().length() > 128) {
            throw new IllegalArgumentException("reasonCode must contain between 1 and 128 characters");
        }
        reasonCode = reasonCode.trim();
        Objects.requireNonNull(state, "state");
        if (deletedRecordCount < 0) throw new IllegalArgumentException("deletedRecordCount must be non-negative");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (approvedByIdentityId != null && approvedByIdentityId.equals(requestedByIdentityId)) {
            throw new IllegalArgumentException("purge requester and approver must differ");
        }
        if (state == State.REQUESTED && (approvedByIdentityId != null || approvedAt != null || completedAt != null)) {
            throw new IllegalArgumentException("REQUESTED purge must not have approval/completion evidence");
        }
        if ((state == State.APPROVED || state == State.RUNNING)
                && (approvedByIdentityId == null || approvedAt == null || completedAt != null)) {
            throw new IllegalArgumentException("approved/running purge requires approval evidence only");
        }
        if ((state == State.SUCCEEDED || state == State.FAILED || state == State.BLOCKED)
                && completedAt == null) {
            throw new IllegalArgumentException("terminal purge requires completion evidence");
        }
    }
}
