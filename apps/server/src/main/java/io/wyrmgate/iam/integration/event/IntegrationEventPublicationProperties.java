package io.wyrmgate.iam.integration.event;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.integration.events")
public record IntegrationEventPublicationProperties(
        boolean enabled,
        Duration pollInterval,
        Duration claimLease,
        Integer batchSize,
        Duration retryBaseDelay,
        Duration retryMaxDelay) {

    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration DEFAULT_CLAIM_LEASE = Duration.ofSeconds(30);
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration DEFAULT_RETRY_MAX_DELAY = Duration.ofMinutes(5);

    public Duration effectivePollInterval() {
        return positive(pollInterval == null ? DEFAULT_POLL_INTERVAL : pollInterval, "poll-interval");
    }

    public Duration effectiveClaimLease() {
        return positive(claimLease == null ? DEFAULT_CLAIM_LEASE : claimLease, "claim-lease");
    }

    public int effectiveBatchSize() {
        int value = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        if (value < 1 || value > 500) {
            throw new IllegalStateException("iam.integration.events.batch-size must be between 1 and 500");
        }
        return value;
    }

    public Duration effectiveRetryBaseDelay() {
        return positive(
                retryBaseDelay == null ? DEFAULT_RETRY_BASE_DELAY : retryBaseDelay,
                "retry-base-delay");
    }

    public Duration effectiveRetryMaxDelay() {
        Duration max = positive(
                retryMaxDelay == null ? DEFAULT_RETRY_MAX_DELAY : retryMaxDelay,
                "retry-max-delay");
        if (max.compareTo(effectiveRetryBaseDelay()) < 0) {
            throw new IllegalStateException(
                    "iam.integration.events.retry-max-delay must be >= retry-base-delay");
        }
        return max;
    }

    private static Duration positive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException("iam.integration.events." + name + " must be positive");
        }
        return value;
    }
}
