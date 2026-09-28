package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.AccessRequest;
import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;

public interface AccessRequestEligibilityEvaluator {

    Evaluation evaluate(
            TenantContext tenant,
            AccessRequest request,
            RequestItem item);

    record Evaluation(
            Status status,
            ApprovalPlanSpec approvalPlan,
            String reasonCode) {
        public Evaluation {
            Objects.requireNonNull(status, "status");
            if (status == Status.ELIGIBLE
                    && approvalPlan == null) {
                throw new IllegalArgumentException(
                        "ELIGIBLE evaluation requires approvalPlan");
            }
            if (status != Status.ELIGIBLE
                    && approvalPlan != null) {
                throw new IllegalArgumentException(
                        "non-eligible evaluation must not carry approvalPlan");
            }
        }

        public static Evaluation eligible(ApprovalPlanSpec plan) {
            return new Evaluation(Status.ELIGIBLE, plan, null);
        }

        public static Evaluation denied(String reasonCode) {
            return new Evaluation(Status.DENIED, null, reasonCode);
        }

        public static Evaluation unavailable(String reasonCode) {
            return new Evaluation(Status.UNAVAILABLE, null, reasonCode);
        }
    }

    enum Status {
        ELIGIBLE,
        DENIED,
        UNAVAILABLE
    }
}
