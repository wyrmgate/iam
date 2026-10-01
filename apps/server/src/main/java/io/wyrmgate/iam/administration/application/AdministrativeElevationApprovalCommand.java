package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Administration-consumer-defined semantic boundary to Governance ApprovalCase. */
public interface AdministrativeElevationApprovalCommand {

    ApprovalReference requestApproval(
            TenantContext tenant,
            UUID elevationId,
            UUID initiatorIdentityId,
            UUID beneficiaryIdentityId,
            Instant now);

    ApprovalStatus currentApproval(TenantContext tenant, UUID elevationId);

    record ApprovalReference(UUID approvalCaseId, String planFingerprint) {}

    record ApprovalStatus(
            UUID approvalCaseId,
            String planFingerprint,
            Outcome outcome) {}

    enum Outcome {
        PENDING,
        APPROVED,
        REJECTED,
        UNAVAILABLE
    }
}
