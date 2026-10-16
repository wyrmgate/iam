package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.domain.PlatformApplicationOffering;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;

/** Semantic governance query for GOVERNED_POLICY platform application admission. */
@FunctionalInterface
public interface PlatformApplicationAdmissionPolicyQuery {

    boolean isTenantAdmitted(
            PlatformApplicationOffering offering,
            TenantContext tenant,
            Instant at);
}
