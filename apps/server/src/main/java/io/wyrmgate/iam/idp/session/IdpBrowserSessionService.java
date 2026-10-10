package io.wyrmgate.iam.idp.session;

import io.wyrmgate.iam.credential.application.CredentialAuthenticationService;
import io.wyrmgate.iam.credential.application.CredentialAuthenticationService.VerifiedCredential;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery.AuthenticationSubject;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-owned browser-session runtime for the first-party IdP.
 *
 * <p>A session is authentication context only. It does not carry Administration permission,
 * application entitlement, AccessAssignment or OAuth scope authority.
 */
public final class IdpBrowserSessionService {

    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);
    public static final Duration DEFAULT_ABSOLUTE_TIMEOUT = Duration.ofHours(8);

    private final IdpBrowserSessionRepository sessions;
    private final IdentityAuthenticationQuery identities;
    private final CredentialAuthenticationService credentials;
    private final IdpSessionTokenCodec tokens;
    private final IdGenerator ids;
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;

    public IdpBrowserSessionService(
            IdpBrowserSessionRepository sessions,
            IdentityAuthenticationQuery identities,
            CredentialAuthenticationService credentials,
            IdpSessionTokenCodec tokens,
            IdGenerator ids) {
        this(
                sessions,
                identities,
                credentials,
                tokens,
                ids,
                DEFAULT_IDLE_TIMEOUT,
                DEFAULT_ABSOLUTE_TIMEOUT);
    }

    public IdpBrowserSessionService(
            IdpBrowserSessionRepository sessions,
            IdentityAuthenticationQuery identities,
            CredentialAuthenticationService credentials,
            IdpSessionTokenCodec tokens,
            IdGenerator ids,
            Duration idleTimeout,
            Duration absoluteTimeout) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.idleTimeout = requirePositive(idleTimeout, "idleTimeout");
        this.absoluteTimeout = requirePositive(absoluteTimeout, "absoluteTimeout");
        if (idleTimeout.compareTo(absoluteTimeout) > 0) {
            throw new IllegalArgumentException("idleTimeout must not exceed absoluteTimeout");
        }
    }

    /** Always creates a fresh opaque token, preventing session fixation across authentication. */
    public EstablishedSession establish(
            TenantContext tenant,
            AuthenticationSubject subject,
            VerifiedCredential credential,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(now, "now");
        if (!subject.principalId().equals(credential.principalId())) {
            throw new IllegalArgumentException("credential proof does not belong to authentication subject");
        }

        IdpSessionTokenCodec.IssuedToken issued = tokens.issue();
        Instant absoluteExpiry = now.plus(absoluteTimeout);
        Instant idleExpiry = minimum(now.plus(idleTimeout), absoluteExpiry);
        IdpBrowserSession session = new IdpBrowserSession(
                ids.nextId(),
                subject.principalId(),
                subject.identityId(),
                credential.credentialId(),
                credential.credentialRevision(),
                issued.hash(),
                now,
                now,
                idleExpiry,
                absoluteExpiry,
                null);
        sessions.insert(tenant, session);
        return new EstablishedSession(
                issued.value(),
                new SessionContext(
                        session.id(),
                        subject.principalId(),
                        subject.identityId(),
                        now,
                        idleExpiry,
                        absoluteExpiry));
    }

    /**
     * Resolves and revalidates a session against current Identity/Principal and Credential state.
     * Invalid sessions are revoked opportunistically and never produce actor context.
     */
    public Optional<SessionContext> resolve(
            TenantContext tenant,
            String rawToken,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(now, "now");
        Optional<String> tokenHash = hash(rawToken);
        if (tokenHash.isEmpty()) return Optional.empty();
        return sessions.findByTokenHash(tenant, tokenHash.orElseThrow())
                .flatMap(session -> revalidate(tenant, session, now));
    }

    /**
     * Resolves Tenant from the globally unique opaque-session digest, then performs the same
     * tenant-scoped lifecycle revalidation. This is the first-party control-plane authentication
     * boundary: the browser supplies only possession proof, never authoritative Tenant.
     */
    public Optional<ResolvedSession> resolve(String rawToken, Instant now) {
        Objects.requireNonNull(now, "now");
        Optional<String> tokenHash = hash(rawToken);
        if (tokenHash.isEmpty()) return Optional.empty();
        return sessions.findByTokenHash(tokenHash.orElseThrow())
                .flatMap(located -> revalidate(located.tenant(), located.session(), now)
                        .map(context -> new ResolvedSession(located.tenant(), context)));
    }

    public void logout(
            TenantContext tenant,
            String rawToken,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(now, "now");
        Optional<String> tokenHash = hash(rawToken);
        if (tokenHash.isEmpty()) return;
        sessions.findByTokenHash(tenant, tokenHash.orElseThrow())
                .ifPresent(session -> revokeQuietly(tenant, session.id(), now));
    }

    /** Revokes a session while deriving Tenant only from the opaque token digest. */
    public void logout(String rawToken, Instant now) {
        Objects.requireNonNull(now, "now");
        Optional<String> tokenHash = hash(rawToken);
        if (tokenHash.isEmpty()) return;
        sessions.findByTokenHash(tokenHash.orElseThrow())
                .ifPresent(located -> revokeQuietly(
                        located.tenant(), located.session().id(), now));
    }

    private Optional<SessionContext> revalidate(
            TenantContext tenant,
            IdpBrowserSession session,
            Instant now) {
        if (!session.usableAt(now)) {
            revokeQuietly(tenant, session.id(), now);
            return Optional.empty();
        }

        Optional<AuthenticationSubject> subject = identities.eligibleSubject(
                tenant, session.principalId());
        if (subject.isEmpty()
                || !subject.get().identityId().equals(session.identityId())
                || !credentials.isStillValid(
                        tenant,
                        session.principalId(),
                        session.credentialId(),
                        session.credentialRevision(),
                        now)) {
            revokeQuietly(tenant, session.id(), now);
            return Optional.empty();
        }

        Instant idleExpiry = minimum(now.plus(idleTimeout), session.absoluteExpiresAt());
        if (!idleExpiry.isAfter(now)
                || !sessions.touch(tenant, session.id(), now, idleExpiry)) {
            return Optional.empty();
        }
        return Optional.of(new SessionContext(
                session.id(),
                session.principalId(),
                session.identityId(),
                session.createdAt(),
                idleExpiry,
                session.absoluteExpiresAt()));
    }

    private Optional<String> hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        try {
            return Optional.of(tokens.hash(rawToken));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private void revokeQuietly(TenantContext tenant, UUID sessionId, Instant now) {
        sessions.revoke(tenant, sessionId, now);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Instant minimum(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    public record ResolvedSession(TenantContext tenant, SessionContext context) {
        public ResolvedSession {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(context, "context");
        }
    }

    public record SessionContext(
            UUID sessionId,
            UUID principalId,
            UUID identityId,
            Instant createdAt,
            Instant idleExpiresAt,
            Instant absoluteExpiresAt) {
        public SessionContext {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(identityId, "identityId");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
            Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        }
    }

    public static final class EstablishedSession {
        private final String token;
        private final SessionContext context;

        EstablishedSession(String token, SessionContext context) {
            this.token = token;
            this.context = context;
        }

        public String token() {
            return token;
        }

        public SessionContext context() {
            return context;
        }

        @Override
        public String toString() {
            return "EstablishedSession[token=REDACTED, context=" + context + "]";
        }
    }
}
