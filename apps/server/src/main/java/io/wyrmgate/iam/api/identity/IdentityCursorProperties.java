package io.wyrmgate.iam.api.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iam.api.cursor")
record IdentityCursorProperties(Duration lifetime) {

    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(15);
    private static final Duration MAX_LIFETIME = Duration.ofHours(24);

    Duration effectiveLifetime() {
        Duration value = lifetime == null ? DEFAULT_LIFETIME : lifetime;
        if (value.isZero() || value.isNegative() || value.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalStateException("iam.api.cursor.lifetime must be greater than zero and at most PT24H");
        }
        return value;
    }
}
