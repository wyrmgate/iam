package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Narrow cross-capability query for whether a governed Identity may act administratively. */
public interface GovernedActorStatusQuery {

    boolean isAdministrativelyEligible(TenantContext tenant, UUID identityId);
}
