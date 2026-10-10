package io.wyrmgate.iam.idp.session;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface IdpBrowserSessionRepository {

    void insert(TenantContext tenant, IdpBrowserSession session);

    Optional<IdpBrowserSession> findByTokenHash(
            TenantContext tenant,
            String tokenHash);

    /**
     * Locates one browser session from its high-entropy opaque-token digest and derives Tenant
     * server-side. The production database enforces global digest uniqueness. Implementations
     * that cannot provide this lookup fail closed by default.
     */
    default Optional<LocatedSession> findByTokenHash(String tokenHash) {
        return Optional.empty();
    }

    boolean touch(
            TenantContext tenant,
            UUID sessionId,
            Instant lastSeenAt,
            Instant idleExpiresAt);

    boolean revoke(
            TenantContext tenant,
            UUID sessionId,
            Instant revokedAt);

    record LocatedSession(TenantContext tenant, IdpBrowserSession session) {
        public LocatedSession {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(session, "session");
        }
    }
}
