package io.wyrmgate.iam.integration.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.integration.worker-auth")
public record ConnectorWorkerAuthProperties(
        boolean enabled,
        String issuerUri,
        String audience) {

    public String requiredIssuerUri() {
        if (issuerUri == null || issuerUri.isBlank()) throw new IllegalStateException("iam.integration.worker-auth.issuer-uri is required");
        return issuerUri;
    }

    public String requiredAudience() {
        if (audience == null || audience.isBlank()) throw new IllegalStateException("iam.integration.worker-auth.audience is required");
        return audience;
    }
}
