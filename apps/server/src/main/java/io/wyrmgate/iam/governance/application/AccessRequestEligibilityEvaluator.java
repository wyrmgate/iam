package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.platform.tenant.TenantContext;

public interface AccessRequestEligibilityEvaluator {

    Evaluation evaluate(
            TenantContext tenant,
            RequestItem item);

    record Evaluation(Status status, String code) {
        public static Evaluation eligible() {
            return new Evaluation(Status.ELIGIBLE, null);
        }

        public static Evaluation denied(String code) {
            return new Evaluation(Status.DENIED, code);
        }

        public static Evaluation unavailable(String code) {
            return new Evaluation(Status.UNAVAILABLE, code);
        }
    }

    enum Status {
        ELIGIBLE,
        DENIED,
        UNAVAILABLE
    }
}
