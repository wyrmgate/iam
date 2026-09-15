package io.wyrmgate.iam.api.security;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.auth")
public record ControlPlaneAuthProperties(boolean enabled, String issuerUri, String audience) {

    public String requiredIssuerUri() {
        String issuer = requireText(issuerUri, "issuer-uri");
        URI parsed;
        try {
            parsed = URI.create(issuer);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("iam.auth.issuer-uri must be a valid absolute URI", exception);
        }
        if (!parsed.isAbsolute()) {
            throw new IllegalStateException("iam.auth.issuer-uri must be a valid absolute URI");
        }
        return issuer;
    }

    public String requiredAudience() {
        return requireText(audience, "audience");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("iam.auth." + name + " is required when authentication is enabled");
        }
        return value;
    }
}
