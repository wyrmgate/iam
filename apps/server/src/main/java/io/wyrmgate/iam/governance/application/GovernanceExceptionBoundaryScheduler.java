package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.GovernanceException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;

public interface GovernanceExceptionBoundaryScheduler {

    String HANDLER_TYPE =
            "governance-exception-boundary";

    void scheduleExpiry(
            TenantContext tenant,
            GovernanceException exception,
            Instant now);
}
