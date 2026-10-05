package io.wyrmgate.iam.authentication.domain;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Authentication-owned tenant-bound OIDC/OAuth client registration. */
public record AuthenticationClient(
        UUID id,
        String clientId,
        String displayName,
        ClientType clientType,
        List<URI> redirectUris,
        List<URI> postLogoutRedirectUris,
        Set<String> scopes,
        LifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AuthenticationClient {
        Objects.requireNonNull(id, "id");
        clientId = requireText(clientId, "clientId", 192);
        displayName = requireText(displayName, "displayName", 256);
        Objects.requireNonNull(clientType, "clientType");
        redirectUris = List.copyOf(Objects.requireNonNull(redirectUris, "redirectUris"));
        postLogoutRedirectUris = List.copyOf(Objects.requireNonNull(postLogoutRedirectUris, "postLogoutRedirectUris"));
        scopes = Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(scopes, "scopes")));
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (redirectUris.isEmpty()) throw new IllegalArgumentException("redirectUris must not be empty");
        redirectUris.forEach(AuthenticationClient::validateRedirectUri);
        postLogoutRedirectUris.forEach(AuthenticationClient::validateRedirectUri);
        if (!scopes.contains("openid")) throw new IllegalArgumentException("OIDC client must allow openid scope");
        for (String scope : scopes) requireText(scope, "scope", 128);
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }

    public enum ClientType { PUBLIC, CONFIDENTIAL }
    public enum LifecycleState { ACTIVE, DISABLED }

    private static void validateRedirectUri(URI uri) {
        Objects.requireNonNull(uri, "redirect URI");
        String scheme = uri.getScheme();
        if (scheme == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("redirect URI must use HTTP(S)");
        }
        if (uri.getFragment() != null) throw new IllegalArgumentException("redirect URI must not contain fragment");
        if ("http".equalsIgnoreCase(scheme)) {
            String host = uri.getHost();
            if (!("127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host))) {
                throw new IllegalArgumentException("HTTP redirect URI is allowed only for loopback development/native clients");
            }
        }
    }

    private static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(name + " exceeds maximum length");
        return normalized;
    }
}