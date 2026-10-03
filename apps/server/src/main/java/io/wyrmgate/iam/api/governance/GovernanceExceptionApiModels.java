package io.wyrmgate.iam.api.governance;

import java.time.Instant;
import java.util.UUID;

final class GovernanceExceptionApiModels {
    private GovernanceExceptionApiModels() {}

    record GovernanceExceptionResource(
            UUID id,
            String scopeKind,
            UUID subjectIdentityId,
            UUID sodRuleId,
            UUID requesterIdentityId,
            String businessReason,
            Instant validFrom,
            Instant validUntil,
            String lifecycleState,
            UUID approvalCaseId,
            UUID predecessorExceptionId,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant approvedAt,
            Instant rejectedAt,
            Instant revokedAt,
            Instant expiredAt) {}
}
