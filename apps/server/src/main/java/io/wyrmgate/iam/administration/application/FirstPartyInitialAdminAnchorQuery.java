package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Semantic query used to prove a first-party Principal is currently authentication-eligible for an Identity. */
public interface FirstPartyInitialAdminAnchorQuery {

    boolean isEligiblePrincipalForIdentity(
            TenantContext tenant,
            UUID principalId,
            UUID identityId);
}
