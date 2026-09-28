package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.AccessRequest;
import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.platform.tenant.TenantContext;

public final class FailClosedAccessRequestEligibilityEvaluator
        implements AccessRequestEligibilityEvaluator {

    @Override
    public Evaluation evaluate(
            TenantContext tenant,
            AccessRequest request,
            RequestItem item) {
        return Evaluation.unavailable(
                "mandatory_governance_evaluator_unavailable");
    }
}
