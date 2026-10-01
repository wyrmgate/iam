package io.wyrmgate.iam.administration.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Canonical provider-neutral authentication assurance context.
 *
 * <p>Provider-specific claim names and raw claims remain outside Administration semantics.</p>
 */
public record AuthenticationAssuranceContext(
        AuthenticationAssuranceLevel level,
        Instant authenticatedAt,
        Instant stepUpAt) {

    public AuthenticationAssuranceContext {
        Objects.requireNonNull(level, "level");
        if (stepUpAt != null && authenticatedAt != null && stepUpAt.isBefore(authenticatedAt)) {
            throw new IllegalArgumentException("stepUpAt must not precede authenticatedAt");
        }
    }

    public static AuthenticationAssuranceContext baseline() {
        return new AuthenticationAssuranceContext(
                AuthenticationAssuranceLevel.BASELINE, null, null);
    }

    public static AuthenticationAssuranceContext strong(Instant authenticatedAt, Instant stepUpAt) {
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(stepUpAt, "stepUpAt");
        return new AuthenticationAssuranceContext(
                AuthenticationAssuranceLevel.STRONG, authenticatedAt, stepUpAt);
    }

    public boolean satisfiesStrongAt(Instant now, Duration maxAge) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(maxAge, "maxAge");
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("maxAge must be positive");
        }
        if (level != AuthenticationAssuranceLevel.STRONG || stepUpAt == null) {
            return false;
        }
        if (stepUpAt.isAfter(now)) {
            return false;
        }
        return !stepUpAt.isBefore(now.minus(maxAge));
    }
}
