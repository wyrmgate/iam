package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.GovernanceException;
import io.wyrmgate.iam.platform.tenant.TenantContext;

public interface GovernanceExceptionChangeSink {

    String EXCEPTION_CHANGED =
            "governance.exception-changed";

    void changed(
            TenantContext tenant,
            GovernanceException exception);
}
