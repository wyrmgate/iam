package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;

public final class CompositeApprovalResultSink
        implements ApprovalResultSink {

    private final List<ApprovalResultSink> delegates;

    public CompositeApprovalResultSink(
            List<ApprovalResultSink> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void approvalResolved(
            TenantContext tenant,
            ApprovalCase approvalCase) {
        for (ApprovalResultSink delegate : delegates) {
            delegate.approvalResolved(
                    tenant, approvalCase);
        }
    }
}
