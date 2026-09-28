package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalApprover;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalDecision;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalPlan;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalStage;
import io.wyrmgate.iam.governance.application.ApprovalModels.CaseState;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionValue;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.SubjectKind;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ApprovalCommandService {

    private final ApprovalRepository repository;
    private final ApprovalResultSink resultSink;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public ApprovalCommandService(
            ApprovalRepository repository,
            ApprovalResultSink resultSink,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resultSink = Objects.requireNonNull(resultSink, "resultSink");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public ApprovalCase start(
            TenantContext tenant,
            SubjectKind subjectKind,
            UUID subjectId,
            UUID initiatorIdentityId,
            PlanSpec planSpec,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(subjectKind, "subjectKind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(initiatorIdentityId, "initiatorIdentityId");
        Objects.requireNonNull(planSpec, "planSpec");
        Objects.requireNonNull(now, "now");

        return transactions.required(() -> {
            var existing = repository.findPendingBySubject(
                    tenant, subjectKind, subjectId);
            if (existing.isPresent()) {
                return existing.get();
            }

            UUID caseId = ids.nextId();
            ApprovalCase approvalCase = new ApprovalCase(
                    caseId,
                    subjectKind,
                    subjectId,
                    initiatorIdentityId,
                    CaseState.PENDING,
                    0,
                    1,
                    now,
                    now,
                    null);
            repository.insertCase(tenant, approvalCase);

            UUID planId = ids.nextId();
            ApprovalPlan plan = new ApprovalPlan(
                    planId,
                    caseId,
                    1,
                    contentHash(planSpec),
                    now);
            repository.insertPlan(tenant, plan);

            int ordinal = 0;
            for (StageSpec stageSpec : planSpec.stages()) {
                UUID stageId = ids.nextId();
                ApprovalStage stage = new ApprovalStage(
                        stageId,
                        planId,
                        ordinal++,
                        stageSpec.decisionMode(),
                        now);
                repository.insertStage(tenant, stage);
                for (UUID approverIdentityId
                        : stageSpec.approverIdentityIds()) {
                    repository.insertApprover(
                            tenant,
                            new ApprovalApprover(
                                    ids.nextId(),
                                    stageId,
                                    approverIdentityId,
                                    now));
                }
            }
            return approvalCase;
        });
    }

    public ApprovalCase decide(
            TenantContext tenant,
            UUID approvalCaseId,
            UUID approverIdentityId,
            DecisionValue decision,
            String reason,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(approvalCaseId, "approvalCaseId");
        Objects.requireNonNull(approverIdentityId, "approverIdentityId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(now, "now");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "expectedRevision must be positive");
        }

        return transactions.required(() -> {
            ApprovalCase current = repository.findCase(
                            tenant, approvalCaseId)
                    .orElseThrow(() -> new ApprovalCommandException(
                            "approval_case_not_found",
                            "The requested ApprovalCase was not found."));
            if (current.state() != CaseState.PENDING) {
                throw new ApprovalCommandException(
                        "approval_case_not_pending",
                        "Only a PENDING ApprovalCase accepts decisions.");
            }

            ApprovalPlan plan = repository.findPlan(
                    tenant, approvalCaseId);
            List<ApprovalStage> stages =
                    repository.findStages(tenant, plan.id());
            ApprovalStage stage = stages.stream()
                    .filter(value -> value.ordinal()
                            == current.currentStageOrdinal())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "current approval stage does not exist"));

            List<ApprovalApprover> approvers =
                    repository.findApprovers(tenant, stage.id());
            boolean resolvedApprover = approvers.stream()
                    .anyMatch(value -> value.approverIdentityId()
                            .equals(approverIdentityId));
            if (!resolvedApprover) {
                throw new ApprovalCommandException(
                        "approval_actor_not_approver",
                        "The actor is not a resolved approver for the current stage.");
            }
            if (current.initiatorIdentityId()
                    .equals(approverIdentityId)) {
                throw new ApprovalCommandException(
                        "approval_self_decision_forbidden",
                        "Self-approval is denied by default.");
            }

            var prior = repository.findDecision(
                    tenant, stage.id(), approverIdentityId);
            if (prior.isPresent()) {
                if (prior.get().decision() == decision) {
                    return current;
                }
                throw new ApprovalCommandException(
                        "approval_decision_conflict",
                        "The approver already recorded a different immutable decision.");
            }

            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "approval-case",
                        approvalCaseId,
                        expectedRevision);
            }

            repository.insertDecision(
                    tenant,
                    new ApprovalDecision(
                            ids.nextId(),
                            current.id(),
                            plan.id(),
                            stage.id(),
                            approverIdentityId,
                            decision,
                            normalizeReason(reason),
                            now));

            ApprovalCase updated;
            if (decision == DecisionValue.REJECT) {
                updated = repository.updateCase(
                        tenant,
                        current.id(),
                        CaseState.REJECTED,
                        current.currentStageOrdinal(),
                        expectedRevision,
                        now,
                        now);
                resultSink.approvalResolved(tenant, updated);
                return updated;
            }

            boolean stageComplete;
            if (stage.decisionMode() == DecisionMode.ANY_ONE) {
                stageComplete = true;
            } else {
                long approvals = repository.findDecisions(
                                tenant, stage.id())
                        .stream()
                        .filter(value ->
                                value.decision()
                                        == DecisionValue.APPROVE)
                        .count();
                stageComplete = approvals == approvers.size();
            }

            int lastOrdinal = stages.getLast().ordinal();
            if (stageComplete
                    && current.currentStageOrdinal()
                            == lastOrdinal) {
                updated = repository.updateCase(
                        tenant,
                        current.id(),
                        CaseState.APPROVED,
                        current.currentStageOrdinal(),
                        expectedRevision,
                        now,
                        now);
                resultSink.approvalResolved(tenant, updated);
                return updated;
            }

            int nextOrdinal = stageComplete
                    ? current.currentStageOrdinal() + 1
                    : current.currentStageOrdinal();
            return repository.updateCase(
                    tenant,
                    current.id(),
                    CaseState.PENDING,
                    nextOrdinal,
                    expectedRevision,
                    now,
                    null);
        });
    }

    private static String normalizeReason(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException(
                    "approval reason must be at most 1000 characters");
        }
        return normalized;
    }

    private static String contentHash(PlanSpec planSpec) {
        StringBuilder canonical = new StringBuilder();
        int ordinal = 0;
        for (StageSpec stage : planSpec.stages()) {
            canonical.append(ordinal++)
                    .append(':')
                    .append(stage.decisionMode().name())
                    .append(':');
            stage.approverIdentityIds().stream()
                    .map(UUID::toString)
                    .sorted()
                    .forEach(value -> canonical.append(value)
                            .append(','));
            canonical.append(';');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString()
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
