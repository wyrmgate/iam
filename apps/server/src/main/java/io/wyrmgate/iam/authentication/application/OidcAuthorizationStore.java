package io.wyrmgate.iam.authentication.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Authentication-owned durable state for the initial OIDC Authorization Code + PKCE flow. */
public interface OidcAuthorizationStore {

    void createRequest(PendingRequest request);

    PendingRequest consumeRequest(String requestSecretDigest, Instant now);

    void createCode(AuthorizationCode code);

    AuthorizationCode consumeCode(String codeSecretDigest, Instant now);

    record PendingRequest(
            UUID id,
            TenantContext tenant,
            UUID clientResourceId,
            String requestSecretDigest,
            String redirectUri,
            Set<String> scopes,
            String clientState,
            String nonce,
            String pkceChallenge,
            Instant expiresAt,
            Instant createdAt) {
        public PendingRequest {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(clientResourceId, "clientResourceId");
            requireText(requestSecretDigest, "requestSecretDigest");
            requireText(redirectUri, "redirectUri");
            scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
            requireText(pkceChallenge, "pkceChallenge");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(createdAt, "createdAt");
            if (scopes.isEmpty() || !scopes.contains("openid")) throw new IllegalArgumentException("openid scope is required");
            if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("request expiry must be after creation");
        }
    }

    record AuthorizationCode(
            UUID id,
            TenantContext tenant,
            UUID clientResourceId,
            UUID sessionId,
            String codeSecretDigest,
            String redirectUri,
            Set<String> scopes,
            String nonce,
            String pkceChallenge,
            Instant expiresAt,
            Instant createdAt) {
        public AuthorizationCode {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(clientResourceId, "clientResourceId");
            Objects.requireNonNull(sessionId, "sessionId");
            requireText(codeSecretDigest, "codeSecretDigest");
            requireText(redirectUri, "redirectUri");
            scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
            requireText(pkceChallenge, "pkceChallenge");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(createdAt, "createdAt");
            if (scopes.isEmpty() || !scopes.contains("openid")) throw new IllegalArgumentException("openid scope is required");
            if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("code expiry must be after creation");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}