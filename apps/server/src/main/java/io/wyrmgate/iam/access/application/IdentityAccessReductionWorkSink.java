package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.IdentityAccessReduction;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Durable Access-owned continuation work for Identity lifecycle access reduction. */
public interface IdentityAccessReductionWorkSink {

    String REDUCTION_REQUESTED =
            "access.identity-access-reduction-requested";

    void reductionRequested(
            TenantContext tenant,
            IdentityAccessReduction reduction,
            UUID correlationId,
            UUID causationId);
}
