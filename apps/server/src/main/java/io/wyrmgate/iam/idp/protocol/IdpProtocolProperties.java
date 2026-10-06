package io.wyrmgate.iam.idp.protocol;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the public first-party OIDC protocol surface. */
@ConfigurationProperties(prefix = "iam.idp")
public record IdpProtocolProperties(
        boolean enabled,
        String issuer) {

    public URI requiredIssuer() {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("iam.idp.issuer is required when first-party IdP is enabled");
        }
        URI value;
        try {
            value = URI.create(issuer.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("iam.idp.issuer must be an absolute URI", exception);
        }
        if (!value.isAbsolute() || value.getHost() == null || value.getUserInfo() != null || value.getFragment() != null) {
            throw new IllegalStateException("iam.idp.issuer must be an absolute HTTP(S) origin/path without user-info or fragment");
        }
        String scheme = value.getScheme();
        if (!"https".equalsIgnoreCase(scheme)
                && !("http".equalsIgnoreCase(scheme) && isLoopback(value.getHost()))) {
            throw new IllegalStateException("iam.idp.issuer must use HTTPS except for loopback development issuers");
        }
        String text = value.toString();
        if (text.endsWith("/")) {
            value = URI.create(text.substring(0, text.length() - 1));
        }
        if (value.getQuery() != null) {
            throw new IllegalStateException("iam.idp.issuer must not contain a query string");
        }
        return value;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }
}
