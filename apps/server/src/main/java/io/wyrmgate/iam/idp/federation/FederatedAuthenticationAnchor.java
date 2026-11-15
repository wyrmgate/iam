package io.wyrmgate.iam.idp.federation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Technical authentication context for a future federated session, never a synthetic Credential. */
public record FederatedAuthenticationAnchor(UUID tenantId, long tenantRevision, UUID trustId, long trustRevision,
        UUID linkId, long linkRevision, UUID principalId, long principalRevision,
        UUID identityId, long identityRevision, Instant authenticatedAt, Instant expiresAt) {
    public FederatedAuthenticationAnchor {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(trustId); Objects.requireNonNull(linkId);
        Objects.requireNonNull(principalId); Objects.requireNonNull(identityId);
        Objects.requireNonNull(authenticatedAt); Objects.requireNonNull(expiresAt);
        if (tenantRevision < 1 || trustRevision < 1 || linkRevision < 1 || principalRevision < 1
                || identityRevision < 1 || !expiresAt.isAfter(authenticatedAt)) {
            throw new IllegalArgumentException("invalid federated anchor");
        }
    }
}
