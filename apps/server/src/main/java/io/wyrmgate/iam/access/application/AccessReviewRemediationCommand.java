package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** Access-owned semantic command for review-driven authoritative access reduction. */
public interface AccessReviewRemediationCommand {

    Result apply(
            TenantContext tenant,
            UUID reviewRemediationId,
            UUID accessAssignmentId,
            Instant now);

    record Result(
            UUID accessAssignmentId,
            Outcome outcome,
            String resultingLifecycleState) {}

    enum Outcome {
        APPLIED,
        NO_ACTION_REQUIRED
    }
}
