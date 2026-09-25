package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;

/** Internal Identity Principal facts; not public integration events. */
public interface PrincipalFactSink {

    String PRINCIPAL_CORRELATED = "identity.principal-correlated";
    String ACCESS_PROJECTION_INPUT_CHANGED =
            "identity.principal-access-projection-input-changed";

    void principalCorrelated(
            TenantContext tenant,
            Principal principal,
            UUID correlationId,
            UUID causationId);
}
