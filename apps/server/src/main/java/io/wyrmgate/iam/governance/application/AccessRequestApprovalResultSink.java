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
    private final AccessRequestEligibilityEvaluator evaluator;
    private final SubmittedRequestItemSink submittedItems;
    private final RequestAdmissionDecisionService decisions;

    public AccessRequestApprovalResultSink(
            AccessRequestRepository requests,
            AccessRequestEligibilityEvaluator evaluator,
            RequestAdmissionDecisionService decisions,
            SubmittedRequestItemSink submittedItems) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.submittedItems = Objects.requireNonNull(submittedItems, "submittedItems");
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

        AccessRequest request = requests.findRequest(
                        tenant, item.accessRequestId())
                .orElseThrow(() -> new IllegalStateException(
                        "AccessRequest does not exist"));

        EligibilityResult result = RequestAdmissionDecisionService.evaluate(evaluator, tenant, request, item);
        RequestItem updated = decisions.finish(tenant, request, item, result, approvalCase.updatedAt());
        if (updated.state() == ItemState.EVALUATING) submittedItems.retryEvaluation(tenant, updated);
    }
}
