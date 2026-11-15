package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Access-consumer-defined command for Governance-owned lifecycle privilege approval. */
public interface LifecycleAccessApprovalCommand {
    record Context(long identityEpoch, UUID lifecyclePolicyVersionId, LifecycleAccessPrivilegeGuard.MaterialContext governance) {
        public Context {
            if (identityEpoch < 1) throw new IllegalArgumentException("positive Identity epoch required");
            java.util.Objects.requireNonNull(lifecyclePolicyVersionId);
            java.util.Objects.requireNonNull(governance);
        }
    }

    boolean currentApprovalSatisfied(
            TenantContext tenant,
            UUID identityId,
            UUID lifecycleRuleId,
            AccessAssignment.TargetKind targetKind,
            UUID targetId, Context context);

    void requestApproval(
            TenantContext tenant,
            UUID identityId,
            UUID lifecycleRuleId,
            AccessAssignment.TargetKind targetKind,
            UUID targetId, Context context,
            Instant at,
            UUID correlationId,
            UUID causationId);
}
