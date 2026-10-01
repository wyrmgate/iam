package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeDelegation;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import java.util.Objects;

/** Delegation plus its current direct source grant for operation-time authorization revalidation. */
public record AdministrativeDelegatedAuthorityCandidate(
        AdministrativeDelegation delegation,
        AdministrativeGrant sourceGrant) {

    public AdministrativeDelegatedAuthorityCandidate {
        Objects.requireNonNull(delegation, "delegation");
        Objects.requireNonNull(sourceGrant, "sourceGrant");
    }
}
