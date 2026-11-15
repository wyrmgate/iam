package io.wyrmgate.iam.idp.session;

import java.time.Instant;
import io.wyrmgate.iam.idp.federation.FederatedAuthenticationAnchor;
import java.util.Objects;
import java.util.UUID;

/** Platform-owned IdP browser-session infrastructure; not governance or access authority. */
public record IdpBrowserSession(
        UUID id,
        UUID principalId,
        UUID identityId,
        UUID credentialId,
        long credentialRevision,
        Long tenantRevision,
        Long principalRevision,
        Long identityRevision,
        String tokenHash,
        Instant createdAt,
        Instant lastSeenAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        Instant revokedAt,
        FederatedAuthenticationAnchor federatedAnchor) {

    public IdpBrowserSession(UUID id,UUID principalId,UUID identityId,UUID credentialId,long credentialRevision,
            Long tenantRevision,Long principalRevision,Long identityRevision,String tokenHash,Instant createdAt,
            Instant lastSeenAt,Instant idleExpiresAt,Instant absoluteExpiresAt,Instant revokedAt) {
        this(id,principalId,identityId,credentialId,credentialRevision,tenantRevision,principalRevision,identityRevision,
                tokenHash,createdAt,lastSeenAt,idleExpiresAt,absoluteExpiresAt,revokedAt,null);
    }
    public Instant authenticatedAt(){return federatedAnchor==null?createdAt:federatedAnchor.authenticatedAt();}

    public IdpBrowserSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(identityId, "identityId");
        if (federatedAnchor==null) Objects.requireNonNull(credentialId,"credentialId");
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        if (federatedAnchor==null && credentialRevision<1) throw new IllegalArgumentException("credentialRevision must be positive");
        if (federatedAnchor!=null && (credentialId!=null || credentialRevision!=0
                || !principalId.equals(federatedAnchor.principalId()) || !identityId.equals(federatedAnchor.identityId())
                || !Objects.equals(tenantRevision,federatedAnchor.tenantRevision())
                || !Objects.equals(principalRevision,federatedAnchor.principalRevision())
                || !Objects.equals(identityRevision,federatedAnchor.identityRevision())
                || federatedAnchor.authenticatedAt().isAfter(createdAt) || absoluteExpiresAt.isAfter(federatedAnchor.expiresAt())))
            throw new IllegalArgumentException("invalid federated session anchor");
        if ((tenantRevision != null && tenantRevision < 1)
                || (principalRevision != null && principalRevision < 1)
                || (identityRevision != null && identityRevision < 1)
                || (revokedAt == null && (tenantRevision == null || principalRevision == null || identityRevision == null))) {
            throw new IllegalArgumentException("usable session requires positive establishing revisions");
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

    @Override public String toString() { return "IdpBrowserSession[id=" + id + ", tokenHash=REDACTED]"; }

    public boolean usableAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return revokedAt == null
                && !at.isBefore(createdAt)
                && at.isBefore(idleExpiresAt)
                && at.isBefore(absoluteExpiresAt);
    }
}
