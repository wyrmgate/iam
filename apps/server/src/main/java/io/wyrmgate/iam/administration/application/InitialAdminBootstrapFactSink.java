package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.InitialAdminBootstrap;
import io.wyrmgate.iam.platform.tenant.TenantContext;

/** Internal semantic fact sink for the security-significant first-admin bootstrap. */
public interface InitialAdminBootstrapFactSink {

    void bootstrapped(TenantContext tenant, InitialAdminBootstrap bootstrap);
}
