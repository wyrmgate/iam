package io.wyrmgate.iam.authentication.protocol;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.authentication")
public record AuthenticationProtocolProperties(
        boolean enabled,
        String issuer,
        Duration authorizationRequestTtl,
        Duration authorizationCodeTtl,
        Duration accessTokenTtl,
        Duration idTokenTtl) {

    public AuthenticationProtocolProperties {
        authorizationRequestTtl = defaulted(authorizationRequestTtl, Duration.ofMinutes(10));
        authorizationCodeTtl = defaulted(authorizationCodeTtl, Duration.ofMinutes(2));
        accessTokenTtl = defaulted(accessTokenTtl, Duration.ofMinutes(5));
        idTokenTtl = defaulted(idTokenTtl, Duration.ofMinutes(5));
        bounded(authorizationRequestTtl, Duration.ofMinutes(30), "authorization-request-ttl");
        bounded(authorizationCodeTtl, Duration.ofMinutes(10), "authorization-code-ttl");
        bounded(accessTokenTtl, Duration.ofMinutes(15), "access-token-ttl");
        bounded(idTokenTtl, Duration.ofMinutes(15), "id-token-ttl");
    }

    public URI requiredIssuer() {
        if (!enabled) throw new IllegalStateException("first-party Authentication is disabled");
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("iam.authentication.issuer is required when first-party Authentication is enabled");
        }
        URI uri = URI.create(issuer.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalStateException("iam.authentication.issuer must be an absolute HTTPS URI without query or fragment");
        }
        return uri;
    }

    private static Duration defaulted(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    private static void bounded(Duration value, Duration maximum, String name) {
        if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalStateException("iam.authentication." + name + " must be positive and at most " + maximum);
        }
    }
}