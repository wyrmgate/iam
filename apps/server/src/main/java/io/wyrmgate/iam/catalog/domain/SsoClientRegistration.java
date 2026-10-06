package io.wyrmgate.iam.catalog.domain;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Catalog-owned authoritative registration input for one public OIDC relying party.
 * Protocol-library client objects are projections of this state, never its owner.
 */
public record SsoClientRegistration(
        UUID id,
        UUID applicationId,
        String clientId,
        Set<String> redirectUris,
        Set<SsoClientScope> allowedScopes,
        boolean requiresGovernedAccess,
        SsoClientLifecycleState lifecycleState,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public SsoClientRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationId, "applicationId");
        clientId = requireText(clientId, "clientId", 200);
        redirectUris = validateRedirectUris(redirectUris);
        allowedScopes = validateScopes(allowedScopes);
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }

    private static Set<String> validateRedirectUris(Set<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("at least one redirect URI is required");
        }
        if (values.size() > 20) throw new IllegalArgumentException("too many redirect URIs");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : values) {
            String value = requireText(raw, "redirectUri", 2048);
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("redirect URI is invalid", invalid);
            }
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("redirect URI must be an absolute HTTP(S) URI without user-info or fragment");
            }
            if (uri.getScheme().equalsIgnoreCase("https")) {
                // production-safe baseline
            } else if (uri.getScheme().equalsIgnoreCase("http") && isLoopback(uri.getHost())) {
                // loopback HTTP is permitted for local/native development only
            } else {
                throw new IllegalArgumentException("redirect URI must use HTTPS except for loopback HTTP");
            }
            normalized.add(uri.toString());
        }
        return Set.copyOf(normalized);
    }

    private static Set<SsoClientScope> validateScopes(Set<SsoClientScope> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("allowedScopes must not be empty");
        if (!values.contains(SsoClientScope.OPENID)) throw new IllegalArgumentException("openid scope is required");
        return Set.copyOf(values);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(name + " exceeds maximum length");
        return normalized;
    }
}
