package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPrincipalState;
import io.wyrmgate.iam.platform.tenant.TenantContext;

public interface DesiredPrincipalFactSink {

    String DESIRED_PRINCIPAL_CHANGED = "access.desired-principal-changed";
    String DESIRED_PRINCIPAL_REVALIDATE = "access.desired-principal-revalidate";

    void changed(TenantContext tenant, DesiredPrincipalState state);

    void revalidate(TenantContext tenant, DesiredPrincipalState state);
}
