package io.wyrmgate.iam.administration.persistence;

import io.wyrmgate.iam.administration.application.FirstPartyInitialAdminAnchorQuery;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Adapter from Administration's bootstrap query port to Identity-owned authentication eligibility. */
public final class IdentityFirstPartyInitialAdminAnchorQueryAdapter implements FirstPartyInitialAdminAnchorQuery {

    private final IdentityAuthenticationQuery identityAuthenticationQuery;

    public IdentityFirstPartyInitialAdminAnchorQueryAdapter(IdentityAuthenticationQuery identityAuthenticationQuery) {
        this.identityAuthenticationQuery = Objects.requireNonNull(identityAuthenticationQuery, "identityAuthenticationQuery");
    }

    @Override
    public boolean isEligiblePrincipalForIdentity(
            TenantContext tenant,
            UUID principalId,
            UUID identityId) {
        return identityAuthenticationQuery.eligibleSubject(tenant, principalId)
                .filter(subject -> subject.identityId().equals(identityId))
                .isPresent();
    }
}
