package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalReadModels.CaseEvidence;
import io.wyrmgate.iam.governance.application.ApprovalReadModels.InboxPage;
import io.wyrmgate.iam.governance.application.ApprovalReadModels.InboxPosition;
import io.wyrmgate.iam.governance.application.ApprovalReadModels.StageEvidence;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ApprovalReadService {

    private final ApprovalRepository repository;

    public ApprovalReadService(
            ApprovalRepository repository) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
    }

    public InboxPage inbox(
            TenantContext tenant,
            UUID approverIdentityId,
            InboxPosition after,
            int limit) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(
                approverIdentityId, "approverIdentityId");
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 200");
        }
        List<ApprovalCase> rows = repository.findInbox(
                tenant,
                approverIdentityId,
                after == null ? null : after.createdAt(),
                after == null ? null : after.id(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<ApprovalCase> items = hasMore
                ? List.copyOf(rows.subList(0, limit))
                : List.copyOf(rows);
        InboxPosition next = hasMore
                ? new InboxPosition(
                        items.getLast().createdAt(),
                        items.getLast().id())
                : null;
        return new InboxPage(items, next);
    }

    public Optional<CaseEvidence> visibleEvidence(
            TenantContext tenant,
            UUID caseId,
            UUID actorIdentityId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(
                actorIdentityId, "actorIdentityId");
        var approvalCase = repository.findCase(
                tenant, caseId);
        if (approvalCase.isEmpty()
                || !repository.isParticipant(
                        tenant, caseId, actorIdentityId)) {
            return Optional.empty();
        }
        var plan = repository.findPlan(
                tenant, caseId);
        List<StageEvidence> stages =
                new ArrayList<>();
        for (var stage : repository.findStages(
                tenant, plan.id())) {
            stages.add(new StageEvidence(
                    stage,
                    repository.findApprovers(
                            tenant, stage.id()),
                    repository.findDecisions(
                            tenant, stage.id())));
        }
        return Optional.of(new CaseEvidence(
                approvalCase.get(),
                plan,
                stages));
    }
}
