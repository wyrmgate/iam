package io.wyrmgate.iam.api.security;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.sso")
public record IdpSsoProperties(boolean enabled, String issuer) {

    public String requiredIssuer() {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("iam.sso.issuer is required when first-party SSO is enabled");
        }
        String normalized = issuer.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        final URI uri;
        try {
            uri = new URI(normalized);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("iam.sso.issuer must be an absolute URI", ex);
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("iam.sso.issuer must be an absolute origin/path URI without user-info, query, or fragment");
        }
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost());
        if (!secure && !loopbackHttp) {
            throw new IllegalStateException("iam.sso.issuer must use HTTPS except for localhost/loopback development");
        }
        return normalized;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }
}
