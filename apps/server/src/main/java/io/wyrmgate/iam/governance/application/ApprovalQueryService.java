package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.SubjectKind;
import io.wyrmgate.iam.governance.application.ApprovalQueryModels.ApprovalEvidence;
import io.wyrmgate.iam.governance.application.ApprovalQueryModels.InboxPage;
import io.wyrmgate.iam.governance.application.ApprovalQueryModels.InboxPosition;
import io.wyrmgate.iam.governance.application.ApprovalQueryModels.StageEvidence;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ApprovalQueryService implements ApprovalResultQuery {

    private final ApprovalRepository repository;

    public ApprovalQueryService(ApprovalRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<ApprovalCase> findBySubject(
            TenantContext tenant,
            SubjectKind subjectKind,
            UUID subjectId) {
        return repository.findLatestBySubject(
                tenant, subjectKind, subjectId);
    }

    public Optional<ApprovalCase> findCase(
            TenantContext tenant,
            UUID caseId) {
        return repository.findCase(tenant, caseId);
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

        List<ApprovalCase> values =
                repository.findPendingForApprover(
                        tenant,
                        approverIdentityId,
                        after == null ? null : after.createdAt(),
                        after == null ? null : after.id(),
                        limit + 1);
        boolean more = values.size() > limit;
        List<ApprovalCase> page = more
                ? List.copyOf(values.subList(0, limit))
                : List.copyOf(values);
        InboxPosition next = more && !page.isEmpty()
                ? new InboxPosition(
                        page.getLast().createdAt(),
                        page.getLast().id())
                : null;
        return new InboxPage(page, next);
    }

    public Optional<ApprovalEvidence> evidence(
            TenantContext tenant,
            UUID caseId) {
        ApprovalCase approvalCase = repository.findCase(
                        tenant, caseId)
                .orElse(null);
        if (approvalCase == null) {
            return Optional.empty();
        }
        var plan = repository.findPlan(
                tenant, approvalCase.id());
        var stages = repository.findStages(
                        tenant, plan.id())
                .stream()
                .map(stage -> new StageEvidence(
                        stage,
                        repository.findApprovers(
                                tenant, stage.id()),
                        repository.findDecisions(
                                tenant, stage.id())))
                .toList();
        return Optional.of(new ApprovalEvidence(
                approvalCase, plan, stages));
    }

    public boolean isParticipant(
            TenantContext tenant,
            UUID caseId,
            UUID identityId) {
        return repository.isParticipant(
                tenant, caseId, identityId);
    }
}
