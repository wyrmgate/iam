package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredGrantState;
import io.wyrmgate.iam.platform.tenant.TenantContext;

public interface DesiredGrantFactSink {

    String DESIRED_GRANT_CHANGED = "access.desired-grant-changed";

    void changed(TenantContext tenant, DesiredGrantState state);
}
