package io.wyrmgate.iam.idp.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Platform-owned IdP browser-session infrastructure; not governance or access authority. */
public record IdpBrowserSession(
        UUID id,
        UUID principalId,
        UUID identityId,
        UUID credentialId,
        long credentialRevision,
        String tokenHash,
        Instant createdAt,
        Instant lastSeenAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        Instant revokedAt) {

    public IdpBrowserSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        if (credentialRevision < 1) {
            throw new IllegalArgumentException("credentialRevision must be positive");
        }
        if (tokenHash.length() != 64) {
            throw new IllegalArgumentException("tokenHash must be a SHA-256 hex digest");
        }
        if (lastSeenAt.isBefore(createdAt)
                || !idleExpiresAt.isAfter(createdAt)
                || !absoluteExpiresAt.isAfter(createdAt)
                || idleExpiresAt.isAfter(absoluteExpiresAt)) {
            throw new IllegalArgumentException("invalid browser session time bounds");
        }
    }

    public boolean usableAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return revokedAt == null
                && at.isBefore(idleExpiresAt)
                && at.isBefore(absoluteExpiresAt);
    }
}
