package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.*;
import io.wyrmgate.iam.platform.id.IdGenerator;
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

public final class ApprovalCaseStartService {

    private final ApprovalRepository repository;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public ApprovalCaseStartService(
            ApprovalRepository repository,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
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

        String requestedHash = contentHash(planSpec);
        return transactions.required(() -> {
            var existing = repository.findPendingBySubject(
                    tenant, subjectKind, subjectId);
            if (existing.isPresent()) {
                ApprovalPlan existingPlan =
                        repository.findPlan(
                                tenant, existing.get().id());
                if (!existingPlan.contentHash()
                        .equals(requestedHash)) {
                    throw new ApprovalCommandException(
                            "approval_case_plan_conflict",
                            "A pending ApprovalCase already exists with a different immutable plan.");
                }
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
                    requestedHash,
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

    public static String contentHash(PlanSpec planSpec) {
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
