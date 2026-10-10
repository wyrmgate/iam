package io.wyrmgate.iam.idp.protocol;

import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Platform-owned short-lived authorization-code state. */
public record IdpAuthorizationCode(
        UUID id,
        UUID clientRegistrationId,
        long clientRegistrationRevision,
        UUID applicationId,
        String clientId,
        UUID browserSessionId,
        UUID principalId,
        UUID identityId,
        String codeHash,
        String redirectUri,
        Set<SsoClientScope> scopes,
        String pkceChallenge,
        String nonce,
        Instant authTime,
        Instant createdAt,
        Instant expiresAt,
        Instant consumedAt) {

    public IdpAuthorizationCode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientRegistrationId, "clientRegistrationId");
        Objects.requireNonNull(applicationId, "applicationId");
        clientId = requireText(clientId, "clientId", 200);
        Objects.requireNonNull(browserSessionId, "browserSessionId");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(identityId, "identityId");
        codeHash = requireHash(codeHash, "codeHash");
        redirectUri = requireText(redirectUri, "redirectUri", 2048);
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
        if (scopes.isEmpty() || !scopes.contains(SsoClientScope.OPENID)) {
            throw new IllegalArgumentException("authorization code scopes must include openid");
        }
        pkceChallenge = requireText(pkceChallenge, "pkceChallenge", 64);
        if (!pkceChallenge.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("pkceChallenge must be an S256 challenge");
        }
        if (nonce != null) nonce = requireText(nonce, "nonce", 512);
        Objects.requireNonNull(authTime, "authTime");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (clientRegistrationRevision < 1) {
            throw new IllegalArgumentException("clientRegistrationRevision must be positive");
        }
        if (authTime.isAfter(createdAt) || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("invalid authorization code time bounds");
        }
        if (consumedAt != null && consumedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("consumedAt must not be before createdAt");
        }
    }

    private static String requireHash(String value, String name) {
        value = requireText(value, name, 64);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        }
        return value;
    }

    private static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(name + " exceeds maximum length");
        }
        return value;
    }
}
