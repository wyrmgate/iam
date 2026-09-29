package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.AccessRequestModels.*;
import io.wyrmgate.iam.governance.application.ApprovalModels.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;

/**
 * Applies reusable approval results back to AccessRequest business state.
 *
 * <p>Final approval is revalidated against current policy/risk/SoD context before
 * authorization. Approval evidence never becomes stale mutation authority.</p>
 */
public final class AccessRequestApprovalResultSink
        implements ApprovalResultSink {

    private final AccessRequestRepository requests;
    private final AuthorizedAccessIntentSink authorizedAccess;
    private final AccessRequestEligibilityEvaluator evaluator;
    private final ApprovalRepository approvals;
    private final ApprovalCaseStartService starter;
    private final SubmittedRequestItemSink submittedItems;

    public AccessRequestApprovalResultSink(
            AccessRequestRepository requests,
            AuthorizedAccessIntentSink authorizedAccess) {
        this(
                requests,
                authorizedAccess,
                null,
                null,
                null,
                null);
    }

    public AccessRequestApprovalResultSink(
            AccessRequestRepository requests,
            AuthorizedAccessIntentSink authorizedAccess,
            AccessRequestEligibilityEvaluator evaluator,
            ApprovalRepository approvals,
            ApprovalCaseStartService starter,
            SubmittedRequestItemSink submittedItems) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.authorizedAccess = Objects.requireNonNull(
                authorizedAccess, "authorizedAccess");
        this.evaluator = evaluator;
        this.approvals = approvals;
        this.starter = starter;
        this.submittedItems = submittedItems;
    }

    @Override
    public void approvalResolved(
            TenantContext tenant,
            ApprovalCase approvalCase) {
        if (approvalCase.subjectKind()
                != SubjectKind.ACCESS_REQUEST_ITEM) {
            return;
        }
        if (approvalCase.state() != CaseState.APPROVED
                && approvalCase.state() != CaseState.REJECTED) {
            return;
        }

        RequestItem item = requests.findItem(
                        tenant, approvalCase.subjectId())
                .orElseThrow(() -> new IllegalStateException(
                        "ApprovalCase subject RequestItem does not exist"));
        if (item.state() != ItemState.PENDING_APPROVAL
                || !approvalCase.id().equals(
                        item.approvalCaseId())) {
            throw new IllegalStateException(
                    "ApprovalCase no longer matches pending RequestItem");
        }

        if (approvalCase.state() == CaseState.REJECTED) {
            requests.updateItemState(
                    tenant,
                    item.id(),
                    ItemState.REJECTED,
                    approvalCase.id(),
                    null,
                    "rejected",
                    item.revision(),
                    approvalCase.updatedAt());
            return;
        }

        if (evaluator == null) {
            authorize(
                    tenant,
                    item,
                    approvalCase,
                    "approved");
            return;
        }

        AccessRequest request = requests.findRequest(
                        tenant, item.accessRequestId())
                .orElseThrow(() -> new IllegalStateException(
                        "AccessRequest does not exist"));

        EligibilityResult result;
        try {
            result = Objects.requireNonNull(
                    evaluator.evaluate(
                            tenant, request, item),
                    "eligibility evaluator result");
        } catch (RuntimeException unavailable) {
            result = EligibilityResult.unavailable(
                    "mandatory_evaluator_unavailable");
        }

        switch (result.outcome()) {
            case AUTHORIZED -> authorize(
                    tenant,
                    item,
                    approvalCase,
                    normalizeCode(result.code()));
            case DENIED -> requests.updateItemState(
                    tenant,
                    item.id(),
                    ItemState.DENIED,
                    approvalCase.id(),
                    null,
                    normalizeCode(result.code()),
                    item.revision(),
                    approvalCase.updatedAt());
            case UNAVAILABLE -> {
                RequestItem evaluating =
                        requests.updateItemState(
                                tenant,
                                item.id(),
                                ItemState.EVALUATING,
                                null,
                                null,
                                normalizeCode(result.code()),
                                item.revision(),
                                approvalCase.updatedAt());
                submittedItems.retryEvaluation(
                        tenant, evaluating);
            }
            case APPROVAL_REQUIRED -> {
                String completedHash = approvals.findPlan(
                                tenant,
                                approvalCase.id())
                        .contentHash();
                String currentHash =
                        ApprovalCaseStartService.contentHash(
                                result.approvalPlan());
                if (completedHash.equals(currentHash)) {
                    authorize(
                            tenant,
                            item,
                            approvalCase,
                            normalizeCode(result.code()));
                } else {
                    ApprovalCase replacement =
                            starter.start(
                                    tenant,
                                    SubjectKind.ACCESS_REQUEST_ITEM,
                                    item.id(),
                                    request.requesterIdentityId(),
                                    result.approvalPlan(),
                                    approvalCase.updatedAt());
                    requests.updateItemState(
                            tenant,
                            item.id(),
                            ItemState.PENDING_APPROVAL,
                            replacement.id(),
                            null,
                            normalizeCode(result.code()),
                            item.revision(),
                            approvalCase.updatedAt());
                }
            }
        }
    }

    private void authorize(
            TenantContext tenant,
            RequestItem item,
            ApprovalCase approvalCase,
            String code) {
        RequestItem updated = requests.updateItemState(
                tenant,
                item.id(),
                ItemState.AUTHORIZED,
                approvalCase.id(),
                null,
                code,
                item.revision(),
                approvalCase.updatedAt());
        authorizedAccess.authorized(
                tenant, updated);
    }

    private static String normalizeCode(
            String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= 128
                ? normalized
                : normalized.substring(0, 128);
    }
}
