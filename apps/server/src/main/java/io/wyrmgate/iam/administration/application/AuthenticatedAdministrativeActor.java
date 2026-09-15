package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Authenticated IAM control-plane actor after transport authentication/actor resolution. */
public record AuthenticatedAdministrativeActor(TenantContext tenant, UUID identityId) {

    public AuthenticatedAdministrativeActor {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
    }
}
