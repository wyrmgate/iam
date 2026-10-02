package io.wyrmgate.iam.administration.notification;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.administration.break-glass.notification")
public record BreakGlassNotificationProperties(
        boolean enabled,
        URI endpoint,
        String secret,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration pollInterval,
        Duration claimLease,
        Integer batchSize,
        Integer maxAttempts,
        Duration retryBaseDelay,
        Duration retryMaxDelay) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration DEFAULT_CLAIM_LEASE = Duration.ofSeconds(30);
    private static final int DEFAULT_BATCH_SIZE = 25;
    private static final int DEFAULT_MAX_ATTEMPTS = 8;
    private static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration DEFAULT_RETRY_MAX_DELAY = Duration.ofMinutes(5);

    public URI requiredEndpoint() {
        if (endpoint == null) throw new IllegalStateException("break-glass notification endpoint is required");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalStateException("break-glass notification endpoint must be an https URL with a host");
        }
        if (endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalStateException("break-glass notification endpoint must not include user info or fragment");
        }
        return endpoint;
    }

    public byte[] requiredSecretBytes() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("break-glass notification secret is required");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("break-glass notification secret must be at least 32 UTF-8 bytes");
        }
        return bytes;
    }

    public Duration effectiveConnectTimeout() { return positive(connectTimeout, DEFAULT_CONNECT_TIMEOUT, "connect-timeout"); }
    public Duration effectiveRequestTimeout() { return positive(requestTimeout, DEFAULT_REQUEST_TIMEOUT, "request-timeout"); }
    public Duration effectivePollInterval() { return positive(pollInterval, DEFAULT_POLL_INTERVAL, "poll-interval"); }
    public Duration effectiveClaimLease() { return positive(claimLease, DEFAULT_CLAIM_LEASE, "claim-lease"); }

    public int effectiveBatchSize() {
        int value = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        if (value < 1 || value > 200) throw new IllegalStateException("break-glass notification batch-size must be between 1 and 200");
        return value;
    }

    public int effectiveMaxAttempts() {
        int value = maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        if (value < 1 || value > 100) throw new IllegalStateException("break-glass notification max-attempts must be between 1 and 100");
        return value;
    }

    public Duration effectiveRetryBaseDelay() { return positive(retryBaseDelay, DEFAULT_RETRY_BASE_DELAY, "retry-base-delay"); }

    public Duration effectiveRetryMaxDelay() {
        Duration value = positive(retryMaxDelay, DEFAULT_RETRY_MAX_DELAY, "retry-max-delay");
        if (value.compareTo(effectiveRetryBaseDelay()) < 0) {
            throw new IllegalStateException("break-glass notification retry-max-delay must be >= retry-base-delay");
        }
        return value;
    }

    @Override
    public String toString() {
        return "BreakGlassNotificationProperties[enabled=" + enabled + ", endpoint=" + endpoint
                + ", secret=<redacted>, connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout + "]";
    }

    private static Duration positive(Duration value, Duration fallback, String name) {
        Duration resolved = value == null ? fallback : value;
        if (resolved.isZero() || resolved.isNegative()) {
            throw new IllegalStateException("break-glass notification " + name + " must be positive");
        }
        return resolved;
    }
}
