package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Access-consumer-defined command for Governance-owned lifecycle privilege approval. */
public interface LifecycleAccessApprovalCommand {
    void requestApproval(
            TenantContext tenant,
            UUID identityId,
            UUID lifecycleRuleId,
            AccessAssignment.TargetKind targetKind,
            UUID targetId,
            Instant at,
            UUID correlationId,
            UUID causationId);
}
