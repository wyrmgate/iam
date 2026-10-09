package io.wyrmgate.iam.idp.session;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdpBrowserSessionRepository {

    void insert(TenantContext tenant, IdpBrowserSession session);

    Optional<IdpBrowserSession> findByTokenHash(
            TenantContext tenant,
            String tokenHash);

    boolean touch(
            TenantContext tenant,
            UUID sessionId,
            Instant lastSeenAt,
            Instant idleExpiresAt);

    boolean revoke(
            TenantContext tenant,
            UUID sessionId,
            Instant revokedAt);
}
