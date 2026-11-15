package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Optional;

/** Access-owned causal result lookup, independent of current eligibility. */
public interface AccessIntentApplicationQuery {
    Optional<AccessIntentCommand.Result> findApplied(TenantContext tenant, AccessIntentCommand.RequestedAccess request);
}
