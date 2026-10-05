package io.wyrmgate.iam.authentication.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authentication-owned SSO session metadata. The bearer-equivalent session secret is never part of this model. */
public record AuthenticationSession(
        UUID id,
        UUID identityId,
        UUID principalId,
        AuthenticationAssurance assurance,
        LifecycleState lifecycleState,
        Instant authenticatedAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant revokedAt,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AuthenticationSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(assurance, "assurance");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!expiresAt.isAfter(authenticatedAt)) throw new IllegalArgumentException("expiresAt must be after authenticatedAt");
        if (lastSeenAt.isBefore(authenticatedAt)) throw new IllegalArgumentException("lastSeenAt must not be before authenticatedAt");
        if (lifecycleState == LifecycleState.REVOKED && revokedAt == null) {
            throw new IllegalArgumentException("revoked session requires revokedAt");
        }
        if (lifecycleState != LifecycleState.REVOKED && revokedAt != null) {
            throw new IllegalArgumentException("only revoked session may carry revokedAt");
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }

    public enum LifecycleState { ACTIVE, REVOKED, EXPIRED }

    public boolean effectiveAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return lifecycleState == LifecycleState.ACTIVE && now.isBefore(expiresAt);
    }
}