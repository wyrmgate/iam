package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupport;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupportPosition;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Access-owned bounded read query for EffectiveAccess explanation evidence. */
public interface EffectiveAccessSupportQuery {
    List<EffectiveSupport> page(
            TenantContext tenant,
            UUID effectiveAccessId,
            EffectiveSupportPosition after,
            int limit,
            Instant at);

    int countCurrent(
            TenantContext tenant,
            UUID effectiveAccessId,
            Instant at);
}
