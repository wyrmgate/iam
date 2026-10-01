package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceContext;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

/** Authenticated IAM control-plane actor after transport authentication/actor resolution. */
public record AuthenticatedAdministrativeActor(
        TenantContext tenant,
        UUID identityId,
        AuthenticationAssuranceContext assurance) {

    public AuthenticatedAdministrativeActor {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(assurance, "assurance");
    }

    public AuthenticatedAdministrativeActor(TenantContext tenant, UUID identityId) {
        this(tenant, identityId, AuthenticationAssuranceContext.baseline());
    }

    public AuthenticatedAdministrativeActor withAssurance(
            AuthenticationAssuranceContext context) {
        return new AuthenticatedAdministrativeActor(tenant, identityId, context);
    }
}
