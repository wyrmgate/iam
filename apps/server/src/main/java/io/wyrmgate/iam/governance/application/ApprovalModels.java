package io.wyrmgate.iam.governance.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ApprovalModels {
    private ApprovalModels() {}

    public enum SubjectKind {
        ACCESS_REQUEST_ITEM,
        ADMINISTRATIVE_ELEVATION,
        ROLE_VERSION_ACTIVATION,
        GOVERNANCE_EXCEPTION
    }

    public enum CaseState {
        PENDING,
        APPROVED,
        REJECTED,
        CANCELLED,
        SUPERSEDED
    }

    public enum DecisionMode {
        ANY_ONE,
        ALL
    }

    public enum DecisionValue {
        APPROVE,
        REJECT
    }

    public record ApprovalCase(
            UUID id,
            SubjectKind subjectKind,
            UUID subjectId,
            UUID initiatorIdentityId,
            CaseState state,
            int currentStageOrdinal,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {
        public ApprovalCase {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(subjectKind, "subjectKind");
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(initiatorIdentityId, "initiatorIdentityId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (currentStageOrdinal < 0) {
                throw new IllegalArgumentException("currentStageOrdinal must not be negative");
            }
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }

    public record ApprovalPlan(
            UUID id,
            UUID approvalCaseId,
            long planNumber,
            String contentHash,
            Instant createdAt) {
        public ApprovalPlan {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(approvalCaseId, "approvalCaseId");
            if (planNumber < 1) {
                throw new IllegalArgumentException("planNumber must be positive");
            }
            if (contentHash == null || contentHash.isBlank()) {
                throw new IllegalArgumentException("contentHash must not be blank");
            }
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record ApprovalStage(
            UUID id,
            UUID approvalPlanId,
            int ordinal,
            DecisionMode decisionMode,
            Instant createdAt) {
        public ApprovalStage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(approvalPlanId, "approvalPlanId");
            Objects.requireNonNull(decisionMode, "decisionMode");
            Objects.requireNonNull(createdAt, "createdAt");
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal must not be negative");
            }
        }
    }

    public record ApprovalApprover(
            UUID id,
            UUID approvalStageId,
            UUID approverIdentityId,
            Instant createdAt) {}

    public record ApprovalDecision(
            UUID id,
            UUID approvalCaseId,
            UUID approvalPlanId,
            UUID approvalStageId,
            UUID approverIdentityId,
            DecisionValue decision,
            String reason,
            Instant decidedAt) {}

    public record StageSpec(
            DecisionMode decisionMode,
            List<UUID> approverIdentityIds) {
        public StageSpec {
            Objects.requireNonNull(decisionMode, "decisionMode");
            approverIdentityIds = List.copyOf(approverIdentityIds);
            if (approverIdentityIds.isEmpty()) {
                throw new IllegalArgumentException("approval stage requires at least one approver");
            }
            if (approverIdentityIds.stream().distinct().count()
                    != approverIdentityIds.size()) {
                throw new IllegalArgumentException("approval stage approvers must be unique");
            }
        }
    }

    public record PlanSpec(List<StageSpec> stages) {
        public PlanSpec {
            stages = List.copyOf(stages);
            if (stages.isEmpty()) {
                throw new IllegalArgumentException("approval plan requires at least one stage");
            }
        }
    }
}
