package io.wyrmgate.iam.identity.persistence;

import io.wyrmgate.iam.governance.application.ApprovalActorEligibilityQuery;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class IdentityApprovalActorEligibilityQuery
        implements ApprovalActorEligibilityQuery {

    private final IdentityRepository identities;

    public IdentityApprovalActorEligibilityQuery(
            IdentityRepository identities) {
        this.identities = Objects.requireNonNull(
                identities, "identities");
    }

    @Override
    public boolean isEligibleApprover(
            TenantContext tenant,
            UUID identityId) {
        return identities.findById(tenant, identityId)
                .map(identity -> identity.lifecycleState()
                        == IdentityLifecycleState.ACTIVE)
                .orElse(false);
    }
}
