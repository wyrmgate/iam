package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemState;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.CaseState;
import io.wyrmgate.iam.governance.application.ApprovalModels.SubjectKind;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;

public final class AccessRequestApprovalResultSink
        implements ApprovalResultSink {

    private final AccessRequestRepository requests;

    public AccessRequestApprovalResultSink(
            AccessRequestRepository requests) {
        this.requests = Objects.requireNonNull(requests, "requests");
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

        requests.updateItemState(
                tenant,
                item.id(),
                approvalCase.state() == CaseState.APPROVED
                        ? ItemState.AUTHORIZED
                        : ItemState.REJECTED,
                approvalCase.id(),
                approvalCase.state() == CaseState.APPROVED
                        ? "approved"
                        : "rejected",
                item.revision(),
                approvalCase.updatedAt());
    }
}
