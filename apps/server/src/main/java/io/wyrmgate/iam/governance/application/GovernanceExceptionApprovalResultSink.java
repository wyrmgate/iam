package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;

public final class GovernanceExceptionApprovalResultSink
        implements ApprovalResultSink {

    private final GovernanceExceptionService service;

    public GovernanceExceptionApprovalResultSink(
            GovernanceExceptionService service) {
        this.service = Objects.requireNonNull(
                service, "service");
    }

    @Override
    public void approvalResolved(
            TenantContext tenant,
            ApprovalCase approvalCase) {
        service.approvalResolved(
                tenant, approvalCase);
    }
}
