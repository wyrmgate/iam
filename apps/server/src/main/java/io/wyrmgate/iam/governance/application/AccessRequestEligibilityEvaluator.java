package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.AccessRequestModels.AccessRequest;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityResult;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.platform.tenant.TenantContext;

@FunctionalInterface
public interface AccessRequestEligibilityEvaluator {
    EligibilityResult evaluate(
            TenantContext tenant,
            AccessRequest request,
            RequestItem item);
}
