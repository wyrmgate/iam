package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LifecycleAccessApprovalCandidateRepository {
    record Candidate(
            UUID id,
            UUID identityId,
            UUID lifecycleRuleId,
            AccessAssignment.TargetKind targetKind,
            UUID targetId,
            UUID policyVersionId,
            String approvalPlanHash,
            io.wyrmgate.iam.access.application.LifecycleAccessApprovalCommand.Context context,
            UUID correlationId,
            UUID causationId,
            Instant createdAt) {}

    Optional<Candidate> findByContext(
            TenantContext tenant,
            UUID identityId,
            UUID lifecycleRuleId,
            AccessAssignment.TargetKind targetKind,
            UUID targetId,
            UUID policyVersionId,
            String approvalPlanHash, io.wyrmgate.iam.access.application.LifecycleAccessApprovalCommand.Context context);

    Optional<Candidate> findById(TenantContext tenant, UUID id);

    void insert(TenantContext tenant, Candidate candidate);
}
