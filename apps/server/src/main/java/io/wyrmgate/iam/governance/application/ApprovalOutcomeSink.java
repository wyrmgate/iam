package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ApprovalPlan;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

public interface ApprovalOutcomeSink {

    String OUTCOME_CHANGED = "governance.approval-outcome-changed";

    void outcomeChanged(
            TenantContext tenant,
            ApprovalPlan plan,
            UUID correlationId,
            UUID causationId);
}
