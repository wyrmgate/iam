package io.wyrmgate.iam.integration.provider.scim;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.integration.scim-local")
public record ScimLocalExecutionProperties(
        boolean enabled,
        Duration pollInterval,
        Duration leaseDuration,
        Integer batchSize) {

    public Duration effectivePollInterval() {
        return positive(pollInterval == null ? Duration.ofSeconds(1) : pollInterval, "poll-interval");
    }

    public Duration effectiveLeaseDuration() {
        Duration value = positive(
                leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration,
                "lease-duration");
        if (value.compareTo(Duration.ofSeconds(5)) < 0
                || value.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalStateException(
                    "iam.integration.scim-local.lease-duration must be between PT5S and PT10M");
        }
        return value;
    }

    public int effectiveBatchSize() {
        int value = batchSize == null ? 10 : batchSize;
        if (value < 1 || value > 100) {
            throw new IllegalStateException(
                    "iam.integration.scim-local.batch-size must be between 1 and 100");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException(
                    "iam.integration.scim-local." + name + " must be positive");
        }
        return value;
    }
}
