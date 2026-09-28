package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ApprovalPlan;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

public interface ApprovalOutcomeSink {

    String OUTCOME_PREFIX = "governance.approval-outcome.";

    static String eventType(io.wyrmgate.iam.governance.domain.ApprovalSubject.Kind kind) {
        return OUTCOME_PREFIX + kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    void outcomeChanged(
            TenantContext tenant,
            ApprovalPlan plan,
            UUID correlationId,
            UUID causationId);
}
