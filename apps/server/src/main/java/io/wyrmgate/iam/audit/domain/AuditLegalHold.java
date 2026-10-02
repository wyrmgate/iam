package io.wyrmgate.iam.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Audit-owned ADR-0035 legal/retention hold evidence. */
public record AuditLegalHold(
        UUID id,
        AuditSelection selection,
        String reasonCode,
        String caseReference,
        State state,
        UUID createdByIdentityId,
        UUID releasedByIdentityId,
        UUID correlationId,
        UUID causationId,
        long revision,
        Instant createdAt,
        Instant releasedAt,
        Instant updatedAt) {

    public enum State { ACTIVE, RELEASED }

    public AuditLegalHold {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(selection, "selection");
        reasonCode = text(reasonCode, 128, "reasonCode");
        caseReference = text(caseReference, 256, "caseReference");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdByIdentityId, "createdByIdentityId");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (state == State.ACTIVE && (releasedByIdentityId != null || releasedAt != null)) {
            throw new IllegalArgumentException("ACTIVE hold must not have release evidence");
        }
        if (state == State.RELEASED && (releasedByIdentityId == null || releasedAt == null)) {
            throw new IllegalArgumentException("RELEASED hold requires release evidence");
        }
    }

    private static String text(String value, int max, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }
}
