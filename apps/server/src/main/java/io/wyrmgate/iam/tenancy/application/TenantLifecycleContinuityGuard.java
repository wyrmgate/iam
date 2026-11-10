package io.wyrmgate.iam.tenancy.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;

/** Administration-supplied semantic guard consumed by Tenancy lifecycle commands. */
public interface TenantLifecycleContinuityGuard {

    void assertMayBecomeActive(TenantContext tenant, Instant at);

    void assertMaySuspendOrDecommission(TenantContext tenant, Instant at);
}
