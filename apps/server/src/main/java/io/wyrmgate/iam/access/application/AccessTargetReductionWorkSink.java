package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessTargetReduction;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Durable Access-owned continuation trigger for Catalog target reduction. */
public interface AccessTargetReductionWorkSink {

    String REDUCTION_REQUESTED =
            "access.catalog-target-reduction-requested";

    void requested(
            TenantContext tenant,
            AccessTargetReduction reduction,
            UUID correlationId,
            UUID causationId);
}
