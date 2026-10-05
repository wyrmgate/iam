package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.authentication.application.AuthenticationSubjectQuery;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.application.PrincipalRepository;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.PrincipalLifecycleState;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned adapter for the narrow Authentication subject eligibility contract. */
public final class IdentityAuthenticationSubjectQuery implements AuthenticationSubjectQuery {

    private final PrincipalRepository principals;
    private final IdentityRepository identities;

    public IdentityAuthenticationSubjectQuery(
            PrincipalRepository principals,
            IdentityRepository identities) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    @Override
    public Result resolve(TenantContext tenant, UUID principalId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        return principals.findById(tenant, principalId)
                .filter(principal -> principal.identityId() != null)
                .map(principal -> {
                    UUID identityId = principal.identityId();
                    boolean eligible = principal.lifecycleState() == PrincipalLifecycleState.ACTIVE
                            && identities.findById(tenant, identityId)
                                    .map(identity -> identity.lifecycleState() == IdentityLifecycleState.ACTIVE)
                                    .orElse(false);
                    return eligible
                            ? Result.eligible(principal.id(), identityId)
                            : Result.ineligible(principal.id(), identityId);
                })
                .orElseGet(Result::unavailable);
    }
}