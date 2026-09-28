package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.platform.tenant.TenantContext;

@FunctionalInterface
public interface ApprovalResultSink {
    void approvalResolved(TenantContext tenant, ApprovalCase approvalCase);
}
