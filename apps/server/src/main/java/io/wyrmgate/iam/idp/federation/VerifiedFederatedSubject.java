package io.wyrmgate.iam.idp.federation;

import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import java.time.Instant;
import java.util.Objects;

/**
 * Data-minimized result of successful upstream authentication.
 *
 * <p>Provider groups, roles, scopes, tenant claims and arbitrary native attributes are
 * intentionally absent. Those inputs do not become Wyrmgate governance or Administration
 * authority merely because an upstream provider asserted them.</p>
 */
public record VerifiedFederatedSubject(
        ExternalAuthenticationSubject subject,
        Instant authenticatedAt) {

    public VerifiedFederatedSubject {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
    }
}
