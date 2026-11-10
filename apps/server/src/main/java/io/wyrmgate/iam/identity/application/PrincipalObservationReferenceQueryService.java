package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Authoritative Principal reference validation without exposing Identity persistence. */
public final class PrincipalObservationReferenceQueryService
        implements PrincipalObservationReferenceQuery {

    private final PrincipalRepository principals;

    public PrincipalObservationReferenceQueryService(PrincipalRepository principals) {
        this.principals = Objects.requireNonNull(principals, "principals");
    }

    @Override
    public Validation validate(
            TenantContext tenant,
            UUID principalId,
            UUID applicationTargetId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(applicationTargetId, "applicationTargetId");
        return principals.findById(tenant, principalId)
                .map(principal -> applicationTargetId.equals(principal.applicationTargetId())
                        ? Validation.VALID
                        : Validation.TARGET_MISMATCH)
                .orElse(Validation.NOT_FOUND);
    }
}
