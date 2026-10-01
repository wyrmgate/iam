package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Constrained policy boundary for emergency authority.
 *
 * <p>Implementations may use deployment policy/configuration, but this is not a generic expression engine.</p>
 */
public interface AdministrativeBreakGlassPolicy {

    Decision evaluate(
            TenantContext tenant,
            UUID actorIdentityId,
            AdministrativeRole requestedRole,
            AdministrativeScope requestedScope,
            Instant requestedValidUntil,
            Instant now);

    record Decision(
            boolean allowed,
            Duration maxValidity,
            Duration maxAssuranceAge,
            String code) {

        public Decision {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("code must not be blank");
            }
            if (allowed) {
                Objects.requireNonNull(maxValidity, "maxValidity");
                Objects.requireNonNull(maxAssuranceAge, "maxAssuranceAge");
                if (maxValidity.isZero() || maxValidity.isNegative()) {
                    throw new IllegalArgumentException("maxValidity must be positive");
                }
                if (maxAssuranceAge.isZero() || maxAssuranceAge.isNegative()) {
                    throw new IllegalArgumentException("maxAssuranceAge must be positive");
                }
            }
        }

        public static Decision allow(Duration maxValidity, Duration maxAssuranceAge) {
            return new Decision(true, maxValidity, maxAssuranceAge, "allowed");
        }

        public static Decision deny(String code) {
            return new Decision(false, null, null, code);
        }
    }
}
