package io.wyrmgate.iam.installation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Trusted deployment configuration for the first-run web setup adapter. */
@ConfigurationProperties(prefix = "iam.bootstrap.web")
public record InstallationWebSetupProperties(
        boolean enabled,
        String setupSecretFile,
        Duration sessionTtl) {

    static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(15);

    Duration effectiveSessionTtl() {
        if (sessionTtl == null) return DEFAULT_SESSION_TTL;
        if (sessionTtl.isNegative() || sessionTtl.isZero() || sessionTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("iam.bootstrap.web.session-ttl must be greater than zero and at most PT1H");
        }
        return sessionTtl;
    }
}
