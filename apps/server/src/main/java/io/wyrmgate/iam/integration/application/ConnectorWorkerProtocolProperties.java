package io.wyrmgate.iam.integration.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.integration.worker-protocol")
public record ConnectorWorkerProtocolProperties(
        Duration sessionLifetime,
        Duration leaseDuration,
        Integer maxClaimSize,
        Integer maxLongPollSeconds,
        Integer maxObservationBatchSize) {

    public Duration effectiveSessionLifetime() {
        return positive(sessionLifetime == null ? Duration.ofMinutes(15) : sessionLifetime, "session-lifetime");
    }

    public Duration effectiveLeaseDuration() {
        Duration value = positive(leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration, "lease-duration");
        if (value.compareTo(Duration.ofSeconds(5)) < 0 || value.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalStateException("worker-protocol lease-duration must be between PT5S and PT10M");
        }
        return value;
    }

    public int effectiveMaxClaimSize() {
        int value = maxClaimSize == null ? 50 : maxClaimSize;
        if (value < 1 || value > 500) throw new IllegalStateException("worker-protocol max-claim-size must be 1..500");
        return value;
    }

    public int effectiveMaxLongPollSeconds() {
        int value = maxLongPollSeconds == null ? 20 : maxLongPollSeconds;
        if (value < 0 || value > 60) throw new IllegalStateException("worker-protocol max-long-poll-seconds must be 0..60");
        return value;
    }

    public int effectiveMaxObservationBatchSize() {
        int value = maxObservationBatchSize == null ? 500 : maxObservationBatchSize;
        if (value < 1 || value > 1000) throw new IllegalStateException("worker-protocol max-observation-batch-size must be 1..1000");
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) throw new IllegalStateException("worker-protocol " + name + " must be positive");
        return value;
    }
}
