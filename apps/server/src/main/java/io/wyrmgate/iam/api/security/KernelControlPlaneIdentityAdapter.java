package io.wyrmgate.iam.api.security;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.vyrnforge.platform.kernel.context.IdentityReference;
import io.vyrnforge.platform.kernel.context.TenantContext;
import java.util.Objects;

/**
 * Narrow technical adapter at the already authenticated control-plane boundary.
 * Kernel identity provenance is not IAM authorization. No raw token, roles, grants,
 * assurance or tenant selection comes from this adapter.
 */
final class KernelControlPlaneIdentityAdapter {
    private KernelControlPlaneIdentityAdapter() {}

    static ExternalAuthenticationSubject externalSubject(String issuer, String subject) {
        // Retain IAM's exact URI, UTF-16 length and whitespace validation and error classification.
        var exact = new ExternalAuthenticationSubject(issuer, subject);
        var provenance = new IdentityReference(exact.issuer(), exact.subject());
        return new ExternalAuthenticationSubject(provenance.issuer(), provenance.subject());
    }

    /**
     * Explicit opt-in for a technical Kernel tenant consumer after server-side IAM resolution.
     * Kernel 0.3.2 requires UUIDv7. Never substitute NONE for a noncanonical ID;
     * existing IAM-only callers retain their original tenant and authority.
     */
    static TenantContext tenantScope(AuthenticatedAdministrativeActor actor) {
        Objects.requireNonNull(actor, "actor");
        return TenantContext.tenant(actor.tenant().tenantId());
    }
}
