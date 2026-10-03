package io.wyrmgate.iam.api.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class GovernancePolicyApiModels {
    private GovernancePolicyApiModels() {}

    record SoDRuleResource(
            UUID id,
            String code,
            UUID leftEntitlementId,
            UUID rightEntitlementId,
            String severity,
            String action,
            Instant createdAt) {}

    record ApprovalStageResource(
            UUID id,
            int ordinal,
            String decisionMode,
            List<UUID> approverIdentityIds,
            Instant createdAt) {}

    record PolicyVersionResource(
            UUID id,
            UUID policyId,
            long versionNumber,
            String state,
            String defaultDecision,
            long revision,
            List<SoDRuleResource> rules,
            List<ApprovalStageResource> approvalStages,
            Instant createdAt,
            Instant updatedAt,
            Instant activatedAt,
            Instant supersededAt) {}
}
