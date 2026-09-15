package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.administration.application.GovernedActorStatusQuery;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned semantic query adapter for Administration actor eligibility checks. */
public final class IdentityGovernedActorStatusQuery implements GovernedActorStatusQuery {

    private final IdentityRepository identityRepository;

    public IdentityGovernedActorStatusQuery(IdentityRepository identityRepository) {
        this.identityRepository = Objects.requireNonNull(identityRepository, "identityRepository");
    }

    @Override
    public boolean isAdministrativelyEligible(TenantContext tenant, UUID identityId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        return identityRepository.findById(tenant, identityId)
                .map(identity -> identity.lifecycleState() == IdentityLifecycleState.ACTIVE)
                .orElse(false);
    }
}
