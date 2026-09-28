package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ApprovalService {

    private final ApprovalRepository repository;
    private final ApprovalOutcomeFactSink outcomes;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public ApprovalService(
            ApprovalRepository repository,
            ApprovalOutcomeFactSink outcomes,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    public ApprovalCase openCase(
            TenantContext tenant,
            ApprovalCase.SubjectType subjectType,
            UUID subjectId,
            long subjectRevision,
            UUID requesterIdentityId,
            ApprovalPlanSpec plan,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");
        if (subjectRevision < 1) {
            throw new IllegalArgumentException(
                    "subjectRevision must be positive");
        }
        validateSelfApproval(plan, requesterIdentityId);

        return transactions.required(() -> {
            ApprovalCase existing = repository.findPendingCaseBySubject(
                            tenant, subjectType, subjectId)
                    .orElse(null);
            if (existing != null) {
                if (existing.subjectRevision() == subjectRevision) {
                    return existing;
                }
                repository.updateCase(
                        tenant,
                        existing.id(),
                        existing.revision(),
                        ApprovalCase.LifecycleState.SUPERSEDED,
                        existing.currentStageOrdinal(),
                        now,
                        now);
            }

            ApprovalCase created = new ApprovalCase(
                    ids.nextId(),
                    subjectType,
                    subjectId,
                    subjectRevision,
                    requesterIdentityId,
                    ApprovalCase.LifecycleState.PENDING,
                    0,
                    1,
                    now,
                    now,
                    null);
            repository.insertCase(tenant, created);

            UUID planId = ids.nextId();
            repository.insertPlan(
                    tenant,
                    planId,
                    created.id(),
                    plan.selfApprovalPolicy(),
                    now);
            for (int ordinal = 0; ordinal < plan.stages().size(); ordinal++) {
                ApprovalPlanSpec.StageSpec stage = plan.stages().get(ordinal);
                UUID stageId = ids.nextId();
                repository.insertStage(
                        tenant,
                        stageId,
                        planId,
                        ordinal,
                        stage.decisionMode(),
                        now);
                for (UUID participant : stage.participantIdentityIds()) {
                    repository.insertParticipant(
                            tenant,
                            ids.nextId(),
                            stageId,
                            participant,
                            now);
                }
            }
            return repository.findCase(tenant, created.id()).orElseThrow();
        });
    }

    public ApprovalCase decide(
            TenantContext tenant,
            UUID approvalCaseId,
            UUID actorIdentityId,
            ApprovalDecision.Decision decision,
            long expectedRevision,
            Instant now,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(approvalCaseId, "approvalCaseId");
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(correlationId, "correlationId");

        return transactions.required(() -> {
            ApprovalCase current = repository.findCase(
                            tenant, approvalCaseId)
                    .orElseThrow(() -> new ApprovalCommandException(
                            "approval_case_not_found",
                            "The requested ApprovalCase was not found."));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "approval-case",
                        approvalCaseId,
                        expectedRevision);
            }
            if (current.lifecycleState()
                    != ApprovalCase.LifecycleState.PENDING) {
                throw new ApprovalCommandException(
                        "approval_case_not_pending",
                        "Only a PENDING ApprovalCase can accept decisions.");
            }

            ApprovalRepository.PlanSnapshot plan =
                    repository.findPlanByCase(
                                    tenant, approvalCaseId)
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "ApprovalCase has no plan"));
            ApprovalRepository.StageSnapshot stage =
                    repository.findStage(
                                    tenant,
                                    approvalCaseId,
                                    current.currentStageOrdinal())
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "ApprovalCase current stage is missing"));

            if (!stage.participantIdentityIds().contains(
                    actorIdentityId)) {
                throw new ApprovalCommandException(
                        "approval_actor_not_participant",
                        "The actor is not a participant in the current approval stage.");
            }
            if (plan.selfApprovalPolicy()
                            == ApprovalPlanSpec.SelfApprovalPolicy.DENY_REQUESTER
                    && actorIdentityId.equals(
                            current.requesterIdentityId())) {
                throw new ApprovalCommandException(
                        "approval_self_decision_denied",
                        "The requester may not approve or reject this ApprovalCase.");
            }
            if (repository.findDecision(
                    tenant,
                    approvalCaseId,
                    stage.stageId(),
                    actorIdentityId).isPresent()) {
                throw new ApprovalCommandException(
                        "approval_already_decided",
                        "The actor already decided the current approval stage.");
            }

            ApprovalDecision evidence = new ApprovalDecision(
                    ids.nextId(),
                    approvalCaseId,
                    stage.stageId(),
                    actorIdentityId,
                    decision,
                    now,
                    correlationId,
                    causationId);
            repository.insertDecision(tenant, evidence);

            if (decision == ApprovalDecision.Decision.REJECT) {
                ApprovalCase rejected = repository.updateCase(
                        tenant,
                        approvalCaseId,
                        expectedRevision,
                        ApprovalCase.LifecycleState.REJECTED,
                        current.currentStageOrdinal(),
                        now,
                        now);
                outcomes.terminalOutcome(
                        tenant, rejected, correlationId, evidence.id());
                return rejected;
            }

            List<ApprovalDecision> decisions =
                    repository.findStageDecisions(
                            tenant,
                            approvalCaseId,
                            stage.stageId());
            boolean stageComplete =
                    stage.decisionMode()
                            == ApprovalPlanSpec.DecisionMode.ANY_ONE
                            ? decisions.stream().anyMatch(d ->
                                    d.decision()
                                            == ApprovalDecision.Decision.APPROVE)
                            : stage.participantIdentityIds().stream()
                                    .allMatch(participant ->
                                            decisions.stream().anyMatch(d ->
                                                    d.participantIdentityId()
                                                            .equals(participant)
                                                    && d.decision()
                                                            == ApprovalDecision.Decision.APPROVE));

            if (!stageComplete) {
                return repository.updateCase(
                        tenant,
                        approvalCaseId,
                        expectedRevision,
                        ApprovalCase.LifecycleState.PENDING,
                        current.currentStageOrdinal(),
                        now,
                        null);
            }

            int nextOrdinal = current.currentStageOrdinal() + 1;
            if (nextOrdinal < plan.stageCount()) {
                return repository.updateCase(
                        tenant,
                        approvalCaseId,
                        expectedRevision,
                        ApprovalCase.LifecycleState.PENDING,
                        nextOrdinal,
                        now,
                        null);
            }

            ApprovalCase approved = repository.updateCase(
                    tenant,
                    approvalCaseId,
                    expectedRevision,
                    ApprovalCase.LifecycleState.APPROVED,
                    current.currentStageOrdinal(),
                    now,
                    now);
            outcomes.terminalOutcome(
                    tenant, approved, correlationId, evidence.id());
            return approved;
        });
    }

    public ApprovalCase supersede(
            TenantContext tenant,
            UUID approvalCaseId,
            long expectedRevision,
            Instant now) {
        return transactions.required(() -> {
            ApprovalCase current = repository.findCase(
                            tenant, approvalCaseId)
                    .orElseThrow(() -> new ApprovalCommandException(
                            "approval_case_not_found",
                            "The requested ApprovalCase was not found."));
            if (current.lifecycleState()
                    != ApprovalCase.LifecycleState.PENDING) {
                return current;
            }
            return repository.updateCase(
                    tenant,
                    approvalCaseId,
                    expectedRevision,
                    ApprovalCase.LifecycleState.SUPERSEDED,
                    current.currentStageOrdinal(),
                    now,
                    now);
        });
    }

    private static void validateSelfApproval(
            ApprovalPlanSpec plan,
            UUID requesterIdentityId) {
        if (requesterIdentityId == null
                || plan.selfApprovalPolicy()
                        == ApprovalPlanSpec.SelfApprovalPolicy.ALLOW_REQUESTER) {
            return;
        }
        boolean included = plan.stages().stream()
                .flatMap(stage ->
                        stage.participantIdentityIds().stream())
                .anyMatch(requesterIdentityId::equals);
        if (included) {
            throw new ApprovalCommandException(
                    "approval_requester_in_plan",
                    "A DENY_REQUESTER plan must not resolve the requester as an approver.");
        }
    }
}
