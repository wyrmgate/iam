package io.wyrmgate.iam.governance.domain;

import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class GovernancePolicyModels {
    private GovernancePolicyModels() {}

    public enum PolicyKind { ACCESS_REQUEST }
    public enum VersionState { DRAFT, READY, ACTIVE, SUPERSEDED, CANCELLED }
    public enum PolicyDecision { AUTHORIZE, REQUIRE_APPROVAL, DENY }
    public enum RiskSeverity { NONE, LOW, MEDIUM, HIGH, CRITICAL }
    public enum SoDAction { REQUIRE_APPROVAL, DENY }
    public enum ConflictSource { CURRENT_ACCESS, REQUEST_ITEM, REQUEST }

    public record Policy(
            UUID id,
            PolicyKind kind,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        public Policy {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        }
    }

    public record PolicyVersion(
            UUID id,
            UUID policyId,
            long versionNumber,
            VersionState state,
            PolicyDecision defaultDecision,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant activatedAt,
            Instant supersededAt) {
        public PolicyVersion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(policyId, "policyId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(defaultDecision, "defaultDecision");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be positive");
            if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        }
    }

    public record SoDRule(
            UUID id,
            UUID policyVersionId,
            String code,
            UUID leftEntitlementId,
            UUID rightEntitlementId,
            RiskSeverity severity,
            SoDAction action,
            Instant createdAt) {
        public SoDRule {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(policyVersionId, "policyVersionId");
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
            Objects.requireNonNull(leftEntitlementId, "leftEntitlementId");
            Objects.requireNonNull(rightEntitlementId, "rightEntitlementId");
            if (leftEntitlementId.equals(rightEntitlementId)) {
                throw new IllegalArgumentException("SoD rule requires two distinct entitlements");
            }
            Objects.requireNonNull(severity, "severity");
            if (severity == RiskSeverity.NONE) {
                throw new IllegalArgumentException("SoD rule severity cannot be NONE");
            }
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record PolicyApprovalStage(
            UUID id,
            UUID policyVersionId,
            int ordinal,
            DecisionMode decisionMode,
            Instant createdAt) {
        public PolicyApprovalStage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(policyVersionId, "policyVersionId");
            Objects.requireNonNull(decisionMode, "decisionMode");
            if (ordinal < 0) throw new IllegalArgumentException("ordinal must not be negative");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record PolicyApprovalApprover(
            UUID id,
            UUID stageId,
            UUID approverIdentityId,
            Instant createdAt) {}

    public record RiskAssessment(
            UUID id,
            UUID requestItemId,
            UUID policyVersionId,
            RiskSeverity severity,
            int factorCount,
            Instant assessedAt) {}

    public record SoDConflict(
            UUID id,
            UUID riskAssessmentId,
            UUID sodRuleId,
            UUID requestedEntitlementId,
            UUID conflictingEntitlementId,
            ConflictSource source,
            RiskSeverity severity,
            SoDAction action,
            UUID governanceExceptionId,
            Instant createdAt) {}

    public record PolicyEvaluation(
            UUID id,
            UUID accessRequestId,
            UUID requestItemId,
            long requestItemRevision,
            UUID policyVersionId,
            UUID riskAssessmentId,
            PolicyDecision decision,
            String code,
            Instant evaluatedAt) {}

    public record PolicySnapshot(
            PolicyVersion version,
            List<SoDRule> rules,
            List<ApprovalStageSnapshot> approvalStages) {
        public PolicySnapshot {
            Objects.requireNonNull(version, "version");
            rules = List.copyOf(rules);
            approvalStages = List.copyOf(approvalStages);
        }
    }

    public record ApprovalStageSnapshot(
            PolicyApprovalStage stage,
            List<PolicyApprovalApprover> approvers) {
        public ApprovalStageSnapshot {
            approvers = List.copyOf(approvers);
        }
    }

    public record SoDRuleSpec(
            String code,
            UUID leftEntitlementId,
            UUID rightEntitlementId,
            RiskSeverity severity,
            SoDAction action) {}
}
