package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;

public final class FailClosedApprovalRequirementsResolver
        implements AccessRequestApprovalRequirementsResolver {

    @Override
    public Resolution resolve(
            TenantContext tenant,
            RequestItem item,
            Instant now) {
        return Resolution.unavailable(
                "approval_policy_unavailable");
    }
}
