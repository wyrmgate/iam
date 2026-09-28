package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;

public interface AccessRequestApprovalRequirementsResolver {

    Resolution resolve(
            TenantContext tenant,
            RequestItem item,
            Instant now);

    record Resolution(
            Status status,
            List<ApprovalService.StageSpec> stages,
            Instant deadlineAt,
            String code) {
        public Resolution {
            stages = stages == null ? List.of() : List.copyOf(stages);
        }

        public static Resolution approvalRequired(
                List<ApprovalService.StageSpec> stages,
                Instant deadlineAt) {
            return new Resolution(
                    Status.APPROVAL_REQUIRED,
                    stages,
                    deadlineAt,
                    null);
        }

        public static Resolution noApproval() {
            return new Resolution(
                    Status.NO_APPROVAL,
                    List.of(),
                    null,
                    null);
        }

        public static Resolution unavailable(String code) {
            return new Resolution(
                    Status.UNAVAILABLE,
                    List.of(),
                    null,
                    code);
        }
    }

    enum Status {
        APPROVAL_REQUIRED,
        NO_APPROVAL,
        UNAVAILABLE
    }
}
