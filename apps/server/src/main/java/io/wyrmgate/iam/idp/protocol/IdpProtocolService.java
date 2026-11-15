package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.idp.runtime.IdpSecurityEvidence;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery.ResolvedClient;
import io.wyrmgate.iam.catalog.domain.SsoClientRegistration;
import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService.SessionContext;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Protocol-neutral OAuth Authorization Code + PKCE runtime.
 *
 * <p>Catalog resolves the public client and therefore the tenant. Browser input never selects a
 * tenant. Platform owns only short-lived authorization state and consumes semantic reads from
 * Catalog, Access, Identity and Credential-owned session validation.</p>
 */
public final class IdpProtocolService {

    public static final Duration DEFAULT_AUTHORIZATION_CODE_LIFETIME = Duration.ofMinutes(5);

    private static final Pattern PKCE_CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern PKCE_VERIFIER = Pattern.compile("[A-Za-z0-9\\-._~]{43,128}");
    private static final Pattern OPAQUE_CODE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SsoClientProtocolQuery clients;
    private final IdpBrowserSessionService sessions;
    private final IdpApplicationAccessQuery applicationAccess;
    private final IdpAuthorizationCodeRepository authorizationCodes;
    private final IdpTokenIssuer tokens;
    private final IdGenerator ids;
    private final IdpSecurityEvidence evidence;
    private final Duration authorizationCodeLifetime;

    public IdpProtocolService(
            SsoClientProtocolQuery clients,
            IdpBrowserSessionService sessions,
            IdpApplicationAccessQuery applicationAccess,
            IdpAuthorizationCodeRepository authorizationCodes,
            IdpTokenIssuer tokens,
            IdGenerator ids, IdpSecurityEvidence evidence) {
        this(
                clients,
                sessions,
                applicationAccess,
                authorizationCodes,
                tokens,
                ids,
                evidence,
                DEFAULT_AUTHORIZATION_CODE_LIFETIME);
    }

    IdpProtocolService(
            SsoClientProtocolQuery clients,
            IdpBrowserSessionService sessions,
            IdpApplicationAccessQuery applicationAccess,
            IdpAuthorizationCodeRepository authorizationCodes,
            IdpTokenIssuer tokens,
            IdGenerator ids,
            IdpSecurityEvidence evidence,
            Duration authorizationCodeLifetime) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.applicationAccess = Objects.requireNonNull(applicationAccess, "applicationAccess");
        this.authorizationCodes = Objects.requireNonNull(authorizationCodes, "authorizationCodes");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.evidence = Objects.requireNonNull(evidence);
        this.authorizationCodeLifetime =
                requirePositive(authorizationCodeLifetime, "authorizationCodeLifetime");
    }

    public AuthorizationResult authorize(
            AuthorizationRequest request,
            String browserSessionToken,
            Instant now) {
        return authorizeValidated(validate(request), browserSessionToken, now);
    }

    ValidatedAuthorization validate(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        ResolvedClient resolved = resolveClient(request.clientId(), "invalid_request");
        SsoClientRegistration client = resolved.registration();
        if (!client.redirectUris().contains(request.redirectUri())) {
            // Never redirect an error to an unregistered URI.
            throw ProtocolException.direct("invalid_request");
        }

        String state = requireState(request.state(), request.redirectUri());
        if (!"code".equals(request.responseType())) {
            throw ProtocolException.redirect(
                    "unsupported_response_type", request.redirectUri(), state);
        }
        Set<SsoClientScope> scopes = requestedScopes(
                request.scope(), client.allowedScopes(), request.redirectUri(), state);
        String challenge = requirePkceChallenge(
                request.codeChallenge(),
                request.codeChallengeMethod(),
                request.redirectUri(),
                state);
        String nonce = optionalNonce(request.nonce(), request.redirectUri(), state);
        return new ValidatedAuthorization(request, resolved, scopes, challenge, nonce);
    }

    record ValidatedAuthorization(AuthorizationRequest request, ResolvedClient resolved,
            Set<SsoClientScope> scopes, String challenge, String nonce) {
        @Override public String toString() { return "ValidatedAuthorization[REDACTED]"; }
    }

    AuthorizationResult authorizeValidated(ValidatedAuthorization validated, String browserSessionToken, Instant now) {
        Objects.requireNonNull(now, "now");
        var request = validated.request();
        var resolved = validated.resolved();
        var client = resolved.registration();
        String state = request.state();
        var scopes = validated.scopes();
        String challenge = validated.challenge();
        String nonce = validated.nonce();
        Optional<SessionContext> eligibleSession;
        try { eligibleSession = sessions.resolve(resolved.tenant(), browserSessionToken, now); }
        catch (RuntimeException unavailable) { throw ProtocolException.redirect("temporarily_unavailable", request.redirectUri(), state); }
        SessionContext session = eligibleSession.orElseThrow(() -> ProtocolException.redirect(
                        "login_required", client.redirectUris().contains(request.redirectUri())
                                ? request.redirectUri()
                                : null, state));

        if (client.requiresGovernedAccess()) {
            final boolean allowed;
            try {
                allowed = applicationAccess.hasCurrentAccess(
                        resolved.tenant(),
                        session.identityId(),
                        client.applicationId(),
                        now);
            } catch (RuntimeException unavailable) {
                // Mandatory privilege evaluation fails closed. Do not persist a code.
                throw ProtocolException.redirect(
                        "temporarily_unavailable", request.redirectUri(), state);
            }
            if (!allowed) {
                evidence.bestEffort(resolved.tenant(), session.identityId(), "authentication:authorize", "sso-client", client.id(),
                        AuditOutcome.DENIED, session.sessionId(), null, now);
                throw ProtocolException.redirect(
                        "access_denied", request.redirectUri(), state);
            }
        }

        String rawCode = randomValue();
        IdpAuthorizationCode code = new IdpAuthorizationCode(
                ids.nextId(),
                client.id(),
                client.revision(),
                client.applicationId(),
                client.clientId(),
                session.sessionId(),
                session.principalId(),
                session.identityId(),
                hex(IdpJwtIssuer.sha256(rawCode.getBytes(StandardCharsets.US_ASCII))),
                request.redirectUri(),
                scopes,
                challenge,
                nonce,
                session.createdAt(),
                now,
                now.plus(authorizationCodeLifetime),
                null);
        try {
            authorizationCodes.insert(resolved.tenant(), code);
            evidence.required(resolved.tenant(), session.identityId(), "authentication:authorize", "idp-code",
                    code.id(), code.id(), session.sessionId(), now);
        } catch (RuntimeException unavailable) {
            throw ProtocolException.redirect("temporarily_unavailable", request.redirectUri(), state);
        }
        return new AuthorizationResult(request.redirectUri(), rawCode, state);
    }

    public TokenResult redeem(TokenRequest request, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");

        if (!"authorization_code".equals(request.grantType())) {
            throw ProtocolException.direct("unsupported_grant_type");
        }
        if (request.code() == null || !OPAQUE_CODE.matcher(request.code()).matches()) {
            throw ProtocolException.direct("invalid_grant");
        }

        ResolvedClient resolved = resolveClient(request.clientId(), "invalid_grant");
        TenantContext tenant = resolved.tenant();
        SsoClientRegistration current = resolved.registration();

        String codeHash = hex(IdpJwtIssuer.sha256(
                request.code().getBytes(StandardCharsets.US_ASCII)));
        IdpAuthorizationCode code = authorizationCodes.consume(tenant, codeHash, now)
                .orElseThrow(() -> ProtocolException.direct("invalid_grant"));

        // A code is burned before validating all bindings so every redemption attempt is single-use.
        if (!current.id().equals(code.clientRegistrationId())
                || current.revision() != code.clientRegistrationRevision()
                || !current.clientId().equals(code.clientId())
                || !current.applicationId().equals(code.applicationId())
                || !current.allowedScopes().containsAll(code.scopes())
                || !Objects.equals(request.redirectUri(), code.redirectUri())
                || !current.redirectUris().contains(code.redirectUri())
                || !PKCE_VERIFIER.matcher(nullToEmpty(request.codeVerifier())).matches()
                || !pkceMatches(request.codeVerifier(), code.pkceChallenge())) {
            deniedCode(tenant, code, now);
            throw ProtocolException.direct("invalid_grant");
        }

        try {
            SessionContext session = sessions.revalidateAuthorizationCodeSession(tenant, code.browserSessionId(), now)
                    .orElseThrow(() -> ProtocolException.direct("invalid_grant"));
            if (!session.sessionId().equals(code.browserSessionId())
                    || !session.principalId().equals(code.principalId())
                    || !session.identityId().equals(code.identityId())
                    || !session.createdAt().equals(code.authTime())) {
                throw ProtocolException.direct("invalid_grant");
            }
            if (current.requiresGovernedAccess() && !applicationAccess.hasCurrentAccess(
                    tenant, session.identityId(), current.applicationId(), now)) {
                throw ProtocolException.direct("invalid_grant");
            }
        } catch (ProtocolException denied) {
            deniedCode(tenant, code, now);
            throw denied;
        } catch (RuntimeException unavailable) {
            throw ProtocolException.direct("temporarily_unavailable");
        }

        final IdpTokenIssuer.TokenPair issued;
        try {
            issued = tokens.issue(tenant, code, now);
            evidence.required(tenant, code.identityId(), "authentication:token", "idp-code",
                    code.id(), code.id(), code.browserSessionId(), now);
        } catch (RuntimeException unavailable) {
            throw ProtocolException.direct("temporarily_unavailable");
        }
        return new TokenResult(
                issued.accessToken(),
                issued.idToken(),
                issued.expiresInSeconds(),
                scopeValue(code.scopes()));
    }

    private void deniedCode(TenantContext tenant, IdpAuthorizationCode code, Instant now) {
        evidence.bestEffort(tenant, null, "authentication:token", "idp-code", code.id(),
                AuditOutcome.DENIED, code.id(), code.browserSessionId(), now);
    }

    private ResolvedClient resolveClient(String clientId, String error) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 200) {
            throw ProtocolException.direct(error);
        }
        return clients.resolveActive(clientId)
                .orElseThrow(() -> ProtocolException.direct(error));
    }

    private static Set<SsoClientScope> requestedScopes(
            String rawScope,
            Set<SsoClientScope> allowedScopes,
            String redirectUri,
            String state) {
        if (rawScope == null || rawScope.isBlank() || rawScope.length() > 256) {
            throw ProtocolException.redirect("invalid_scope", redirectUri, state);
        }
        // OAuth scope syntax is ASCII space-delimited. Reject tabs/newlines rather than normalizing them.
        if (rawScope.indexOf('\t') >= 0
                || rawScope.indexOf('\r') >= 0
                || rawScope.indexOf('\n') >= 0) {
            throw ProtocolException.redirect("invalid_scope", redirectUri, state);
        }
        LinkedHashSet<SsoClientScope> requested = new LinkedHashSet<>();
        for (String value : rawScope.trim().split(" +")) {
            try {
                requested.add(SsoClientScope.fromProtocolValue(value));
            } catch (IllegalArgumentException unsupported) {
                throw ProtocolException.redirect("invalid_scope", redirectUri, state);
            }
        }
        if (!requested.contains(SsoClientScope.OPENID)
                || !allowedScopes.containsAll(requested)) {
            throw ProtocolException.redirect("invalid_scope", redirectUri, state);
        }
        return Set.copyOf(requested);
    }

    private static String requirePkceChallenge(
            String challenge,
            String method,
            String redirectUri,
            String state) {
        if (!"S256".equals(method)
                || challenge == null
                || !PKCE_CHALLENGE.matcher(challenge).matches()) {
            throw ProtocolException.redirect("invalid_request", redirectUri, state);
        }
        return challenge;
    }

    private static String requireState(String state, String redirectUri) {
        if (state == null
                || state.length() < 16
                || state.length() > 512
                || hasControlCharacter(state)) {
            throw ProtocolException.redirect("invalid_request", redirectUri, null);
        }
        return state;
    }

    private static String optionalNonce(
            String nonce,
            String redirectUri,
            String state) {
        if (nonce == null) return null;
        if (nonce.length() < 16
                || nonce.length() > 512
                || hasControlCharacter(nonce)) {
            throw ProtocolException.redirect("invalid_request", redirectUri, state);
        }
        return nonce;
    }

    private static boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static boolean pkceMatches(String verifier, String expectedChallenge) {
        String actual = Base64.getUrlEncoder().withoutPadding().encodeToString(
                IdpJwtIssuer.sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                expectedChallenge.getBytes(StandardCharsets.US_ASCII));
    }

    static String scopeValue(Set<SsoClientScope> scopes) {
        return scopes.stream()
                .map(SsoClientScope::protocolValue)
                .sorted()
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public record AuthorizationRequest(
            String responseType,
            String clientId,
            String redirectUri,
            String scope,
            String state,
            String codeChallenge,
            String codeChallengeMethod,
            String nonce) {
        @Override public String toString() { return "AuthorizationRequest[REDACTED]"; }
    }

    public record AuthorizationResult(
            String redirectUri,
            String code,
            String state) {
        @Override public String toString() { return "AuthorizationResult[REDACTED]"; }
    }

    public record TokenRequest(
            String grantType,
            String code,
            String clientId,
            String redirectUri,
            String codeVerifier) {
        @Override public String toString() { return "TokenRequest[REDACTED]"; }
    }

    public record TokenResult(
            String accessToken,
            String idToken,
            long expiresInSeconds,
            String scope) {
        @Override public String toString() { return "TokenResult[REDACTED]"; }
    }

    public static final class ProtocolException extends RuntimeException {
        private final String error;
        private final String redirectUri;
        private final String state;

        private ProtocolException(String error, String redirectUri, String state) {
            super(error, null, false, false);
            this.error = error;
            this.redirectUri = redirectUri;
            this.state = state;
        }

        static ProtocolException direct(String error) {
            return new ProtocolException(error, null, null);
        }

        static ProtocolException redirect(String error, String redirectUri, String state) {
            return new ProtocolException(error, redirectUri, state);
        }

        public String error() {
            return error;
        }

        public Optional<String> redirectUri() {
            return Optional.ofNullable(redirectUri);
        }

        public Optional<String> state() {
            return Optional.ofNullable(state);
        }
    }
}
