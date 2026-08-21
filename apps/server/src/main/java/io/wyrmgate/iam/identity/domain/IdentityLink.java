package io.wyrmgate.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative correlation history between one source observation and a canonical Identity. */
public record IdentityLink(
        UUID id,
        UUID sourceRecordId,
        UUID identityId,
        LinkState state,
        Instant linkedAt,
        Instant endedAt,
        String correlationReason,
        UUID correlationId,
        UUID causationId) {

    public enum LinkState {
        ACCEPTED,
        SUPERSEDED
    }

    public IdentityLink {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(linkedAt, "linkedAt");
        if (correlationReason == null || correlationReason.isBlank()) {
            throw new IllegalArgumentException("correlationReason must not be blank");
        }
        Objects.requireNonNull(correlationId, "correlationId");
        if (state == LinkState.ACCEPTED && endedAt != null) {
            throw new IllegalArgumentException("accepted link must not have endedAt");
        }
        if (state == LinkState.SUPERSEDED && endedAt == null) {
            throw new IllegalArgumentException("superseded link requires endedAt");
        }
        if (endedAt != null && endedAt.isBefore(linkedAt)) {
            throw new IllegalArgumentException("endedAt must not precede linkedAt");
        }
    }
}
