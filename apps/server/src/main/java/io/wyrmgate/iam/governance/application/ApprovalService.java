package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.governance.domain.ApprovalParticipant;
import io.wyrmgate.iam.governance.domain.ApprovalPlan;
import io.wyrmgate.iam.governance.domain.ApprovalStage;
import io.wyrmgate.iam.governance.domain.ApprovalSubject;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ApprovalService {

    private final ApprovalRepository repository;
    private final ApprovalOutcomeSink outcomes;
    private final ApprovalActorEligibilityQuery actorEligibility;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public ApprovalService(
            ApprovalRepository repository,
            ApprovalOutcomeSink outcomes,
            ApprovalActorEligibilityQuery actorEligibility,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.actorEligibility = Objects.requireNonNull(
                actorEligibility, "actorEligibility");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public ApprovalPlan createPlan(
            TenantContext tenant,
            ApprovalSubject subject,
            String requirementsFingerprint,
            List<StageSpec> stages,
            Instant deadlineAt,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(subject, "subject");
        if (requirementsFingerprint == null || requirementsFingerprint.isBlank()) {
            throw new ApprovalCommandException(
                    "approval_requirements_fingerprint_required",
                    "ApprovalPlan requires a non-blank requirements fingerprint.");
        }
        Objects.requireNonNull(stages, "stages");
        Objects.requireNonNull(now, "now");
        if (stages.isEmpty()) {
            throw new ApprovalCommandException(
                    "approval_plan_empty",
                    "ApprovalPlan requires at least one stage.");
        }
        if (deadlineAt != null && !deadlineAt.isAfter(now)) {
            throw new ApprovalCommandException(
                    "approval_deadline_invalid",
                    "ApprovalPlan deadline must be in the future.");
        }
        for (StageSpec stage : stages) {
            if (stage.approverIdentityIds().isEmpty()) {
                throw new ApprovalCommandException(
                        "approval_stage_empty",
                        "Every approval stage requires at least one approver.");
            }
            if (new HashSet<>(stage.approverIdentityIds()).size()
                    != stage.approverIdentityIds().size()) {
                throw new ApprovalCommandException(
                        "approval_stage_duplicate_approver",
                        "An approver may appear only once in one approval stage.");
            }
            for (UUID approverId : stage.approverIdentityIds()) {
                if (!actorEligibility.isEligibleApprover(
                        tenant, approverId)) {
                    throw new ApprovalCommandException(
                            "approval_actor_ineligible",
                            "ApprovalPlan participants must be active governed Identities.");
                }
            }
        }

        return transactions.required(() -> {
            if (repository.findPendingBySubject(tenant, subject).isPresent()) {
                throw new ApprovalCommandException(
                        "approval_plan_already_pending",
                        "The subject already has a pending ApprovalPlan.");
            }

            ApprovalPlan plan = new ApprovalPlan(
                    ids.nextId(),
                    subject,
                    requirementsFingerprint,
                    ApprovalPlan.LifecycleState.PENDING,
                    0,
                    deadlineAt,
                    1,
                    now,
                    now,
                    null);
            repository.insertPlan(tenant, plan);

            for (int stageOrdinal = 0; stageOrdinal < stages.size(); stageOrdinal++) {
                StageSpec spec = stages.get(stageOrdinal);
                ApprovalStage stage = new ApprovalStage(
                        ids.nextId(),
                        plan.id(),
                        stageOrdinal,
                        spec.decisionMode(),
                        stageOrdinal == 0
                                ? ApprovalStage.LifecycleState.ACTIVE
                                : ApprovalStage.LifecycleState.WAITING,
                        now,
                        now);
                repository.insertStage(tenant, stage);
                for (int participantOrdinal = 0;
                        participantOrdinal < spec.approverIdentityIds().size();
                        participantOrdinal++) {
                    repository.insertParticipant(
                            tenant,
                            new ApprovalParticipant(
                                    ids.nextId(),
                                    stage.id(),
                                    spec.approverIdentityIds()
                                            .get(participantOrdinal),
                                    participantOrdinal,
                                    now));
                }
            }
            return repository.findPlan(tenant, plan.id()).orElseThrow();
        });
    }

    public ApprovalPlan decide(
            TenantContext tenant,
            UUID planId,
            UUID approverIdentityId,
            ApprovalDecision.Value decision,
            String reason,
            long expectedRevision,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(approverIdentityId, "approverIdentityId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(now, "now");

        DecisionResult result = transactions.required(() -> {
            ApprovalPlan plan = requirePending(
                    tenant, planId, expectedRevision);

            if (plan.isExpiredAt(now)) {
                ApprovalPlan expired = repository.updatePlan(
                        tenant,
                        plan.id(),
                        ApprovalPlan.LifecycleState.EXPIRED,
                        plan.currentStageOrdinal(),
                        expectedRevision,
                        now,
                        now);
                repository.skipStagesAfter(
                        tenant, plan.id(), plan.currentStageOrdinal() - 1, now);
                outcomes.outcomeChanged(
                        tenant, expired, correlationId, causationId);
                return DecisionResult.expired(expired);
            }

            ApprovalStage stage = repository.findStage(
                            tenant,
                            plan.id(),
                            plan.currentStageOrdinal())
                    .orElseThrow(() -> new IllegalStateException(
                            "ApprovalPlan current stage does not exist"));
            if (stage.lifecycleState()
                    != ApprovalStage.LifecycleState.ACTIVE) {
                throw new IllegalStateException(
                        "ApprovalPlan current stage is not ACTIVE");
            }

            List<ApprovalParticipant> participants =
                    repository.findParticipants(tenant, stage.id());
            boolean assigned = participants.stream()
                    .anyMatch(p -> p.approverIdentityId()
                            .equals(approverIdentityId));
            if (!assigned) {
                throw new ApprovalCommandException(
                        "approval_actor_not_participant",
                        "The actor is not an approver for the active stage.");
            }

            if (!actorEligibility.isEligibleApprover(
                    tenant, approverIdentityId)) {
                throw new ApprovalCommandException(
                        "approval_actor_ineligible",
                        "The approver Identity is not currently eligible to decide.");
            }

            var existing = repository.findDecision(
                    tenant, stage.id(), approverIdentityId);
            if (existing.isPresent()) {
                if (existing.get().value() == decision) {
                    return DecisionResult.accepted(plan);
                }
                throw new ApprovalCommandException(
                        "approval_decision_already_recorded",
                        "The approver already recorded a different decision.");
            }

            repository.insertDecision(
                    tenant,
                    new ApprovalDecision(
                            ids.nextId(),
                            plan.id(),
                            stage.id(),
                            approverIdentityId,
                            decision,
                            reason,
                            correlationId,
                            causationId,
                            now));

            if (decision == ApprovalDecision.Value.REJECT) {
                repository.updateStageState(
                        tenant,
                        stage.id(),
                        ApprovalStage.LifecycleState.REJECTED,
                        now);
                repository.skipStagesAfter(
                        tenant, plan.id(), stage.ordinal(), now);
                ApprovalPlan rejected = repository.updatePlan(
                        tenant,
                        plan.id(),
                        ApprovalPlan.LifecycleState.REJECTED,
                        stage.ordinal(),
                        expectedRevision,
                        now,
                        now);
                outcomes.outcomeChanged(
                        tenant, rejected, correlationId, causationId);
                return DecisionResult.accepted(rejected);
            }

            List<ApprovalDecision> decisions =
                    repository.findDecisions(tenant, stage.id());
            long approvals = decisions.stream()
                    .filter(value ->
                            value.value() == ApprovalDecision.Value.APPROVE)
                    .count();

            boolean stageApproved = switch (stage.decisionMode()) {
                case ANY_ONE -> approvals >= 1;
                case ALL -> approvals == participants.size();
            };

            if (!stageApproved) {
                ApprovalPlan bumped = repository.updatePlan(
                        tenant,
                        plan.id(),
                        ApprovalPlan.LifecycleState.PENDING,
                        stage.ordinal(),
                        expectedRevision,
                        now,
                        null);
                return DecisionResult.accepted(bumped);
            }

            repository.updateStageState(
                    tenant,
                    stage.id(),
                    ApprovalStage.LifecycleState.APPROVED,
                    now);
            List<ApprovalStage> stages =
                    repository.findStages(tenant, plan.id());
            int nextOrdinal = stage.ordinal() + 1;
            if (nextOrdinal >= stages.size()) {
                ApprovalPlan approved = repository.updatePlan(
                        tenant,
                        plan.id(),
                        ApprovalPlan.LifecycleState.APPROVED,
                        stage.ordinal(),
                        expectedRevision,
                        now,
                        now);
                outcomes.outcomeChanged(
                        tenant, approved, correlationId, causationId);
                return DecisionResult.accepted(approved);
            }

            ApprovalStage next = stages.get(nextOrdinal);
            repository.updateStageState(
                    tenant,
                    next.id(),
                    ApprovalStage.LifecycleState.ACTIVE,
                    now);
            ApprovalPlan advanced = repository.updatePlan(
                    tenant,
                    plan.id(),
                    ApprovalPlan.LifecycleState.PENDING,
                    nextOrdinal,
                    expectedRevision,
                    now,
                    null);
            return DecisionResult.accepted(advanced);
        });

        if (result.expired()) {
            throw new ApprovalCommandException(
                    "approval_plan_expired",
                    "The ApprovalPlan deadline has passed.");
        }
        return result.plan();
    }

    public ApprovalPlan supersede(
            TenantContext tenant,
            UUID planId,
            long expectedRevision,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        return transactions.required(() -> {
            ApprovalPlan plan = requirePending(
                    tenant, planId, expectedRevision);
            repository.skipStagesAfter(
                    tenant, plan.id(), -1, now);
            ApprovalPlan superseded = repository.updatePlan(
                    tenant,
                    plan.id(),
                    ApprovalPlan.LifecycleState.SUPERSEDED,
                    plan.currentStageOrdinal(),
                    expectedRevision,
                    now,
                    now);
            outcomes.outcomeChanged(
                    tenant, superseded, correlationId, causationId);
            return superseded;
        });
    }

    private ApprovalPlan requirePending(
            TenantContext tenant,
            UUID planId,
            long expectedRevision) {
        ApprovalPlan plan = repository.findPlan(tenant, planId)
                .orElseThrow(() -> new ApprovalCommandException(
                        "approval_plan_not_found",
                        "The ApprovalPlan was not found."));
        if (plan.revision() != expectedRevision) {
            throw new StaleWriteException(
                    "approval-plan", planId, expectedRevision);
        }
        if (plan.lifecycleState() != ApprovalPlan.LifecycleState.PENDING) {
            throw new ApprovalCommandException(
                    "approval_plan_terminal",
                    "The ApprovalPlan no longer accepts decisions.");
        }
        return plan;
    }

    public record StageSpec(
            ApprovalStage.DecisionMode decisionMode,
            List<UUID> approverIdentityIds) {
        public StageSpec {
            Objects.requireNonNull(decisionMode, "decisionMode");
            approverIdentityIds = List.copyOf(
                    Objects.requireNonNull(
                            approverIdentityIds,
                            "approverIdentityIds"));
        }
    }

    private record DecisionResult(
            ApprovalPlan plan,
            boolean expired) {
        static DecisionResult accepted(ApprovalPlan plan) {
            return new DecisionResult(plan, false);
        }

        static DecisionResult expired(ApprovalPlan plan) {
            return new DecisionResult(plan, true);
        }
    }
}
