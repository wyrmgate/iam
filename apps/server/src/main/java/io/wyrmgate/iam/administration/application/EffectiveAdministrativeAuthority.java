package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeAuthoritySource;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One Administration-derived authority currently effective for the authenticated governed actor.
 *
 * <p>This is a read projection for operator-console optimization. It is never an authorization
 * decision and must not be cached as a substitute for operation-time evaluation.</p>
 */
public record EffectiveAdministrativeAuthority(
        AdministrativePermission permission,
        AdministrativeScope scope,
        AdministrativeAuthoritySource source,
        UUID sourceId,
        Instant validFrom,
        Instant validUntil) {

    public EffectiveAdministrativeAuthority {
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceId, "sourceId");
    }
}
