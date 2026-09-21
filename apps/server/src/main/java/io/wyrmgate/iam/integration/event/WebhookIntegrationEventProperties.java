package io.wyrmgate.iam.integration.event;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.integration.events.webhook")
public record WebhookIntegrationEventProperties(
        URI endpoint,
        String secret,
        Duration connectTimeout,
        Duration requestTimeout) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    public URI requiredEndpoint() {
        if (endpoint == null) {
            throw new IllegalStateException("iam.integration.events.webhook.endpoint is required");
        }
        if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalStateException("iam.integration.events.webhook.endpoint must use https");
        }
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalStateException("iam.integration.events.webhook.endpoint must include a host");
        }
        if (endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalStateException(
                    "iam.integration.events.webhook.endpoint must not include user info or fragment");
        }
        return endpoint;
    }

    public byte[] requiredSecretBytes() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("iam.integration.events.webhook.secret is required");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "iam.integration.events.webhook.secret must be at least 32 UTF-8 bytes");
        }
        return bytes;
    }

    public Duration effectiveConnectTimeout() {
        return positive(
                connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout,
                "connect-timeout");
    }

    public Duration effectiveRequestTimeout() {
        return positive(
                requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout,
                "request-timeout");
    }

    private static Duration positive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException(
                    "iam.integration.events.webhook." + name + " must be positive");
        }
        return value;
    }
}
