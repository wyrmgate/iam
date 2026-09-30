package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Access-consumer-defined Governance guard for automatic privilege increases. */
public interface LifecycleAccessPrivilegeGuard {

    Result evaluate(
            TenantContext tenant,
            UUID identityId,
            UUID lifecycleRuleId,
            AccessAssignment.TargetKind targetKind,
            UUID targetId,
            Instant at,
            UUID correlationId,
            UUID causationId);

    enum Decision {
        AUTHORIZE,
        REQUIRE_APPROVAL,
        DENY,
        UNAVAILABLE
    }

    record Result(Decision decision, String code) {
        public Result {
            Objects.requireNonNull(decision, "decision");
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("code is required");
            }
        }

        public static Result authorize() {
            return new Result(Decision.AUTHORIZE, "authorized");
        }

        public static Result requireApproval() {
            return new Result(Decision.REQUIRE_APPROVAL, "approval_required");
        }

        public static Result deny() {
            return new Result(Decision.DENY, "denied");
        }

        public static Result unavailable(String code) {
            return new Result(Decision.UNAVAILABLE, code);
        }
    }
}
