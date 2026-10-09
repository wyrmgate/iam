package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.identity.domain.PrincipalLifecycleState;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Identity-owned implementation of first-party authentication subject eligibility. */
public final class IdentityAuthenticationQueryService
        implements IdentityAuthenticationQuery {

    private final PrincipalRepository principals;
    private final IdentityRepository identities;

    public IdentityAuthenticationQueryService(
            PrincipalRepository principals,
            IdentityRepository identities) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    @Override
    public Optional<AuthenticationSubject> resolveEligible(
            TenantContext tenant,
            UUID applicationTargetId,
            String nativePrincipalKey) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(applicationTargetId, "applicationTargetId");
        if (nativePrincipalKey == null || nativePrincipalKey.isBlank()) {
            return Optional.empty();
        }
        return principals.findByTargetAndNativeKey(
                        tenant,
                        applicationTargetId,
                        nativePrincipalKey.trim())
                .flatMap(principal -> eligible(tenant, principal));
    }

    @Override
    public Optional<AuthenticationSubject> eligibleSubject(
            TenantContext tenant,
            UUID principalId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principalId, "principalId");
        return principals.findById(tenant, principalId)
                .flatMap(principal -> eligible(tenant, principal));
    }

    private Optional<AuthenticationSubject> eligible(
            TenantContext tenant,
            Principal principal) {
        if (principal.lifecycleState() != PrincipalLifecycleState.ACTIVE
                || principal.identityId() == null) {
            return Optional.empty();
        }
        return identities.findById(tenant, principal.identityId())
                .filter(identity -> identity.lifecycleState() == IdentityLifecycleState.ACTIVE)
                .map(identity -> new AuthenticationSubject(
                        principal.id(),
                        identity.id(),
                        principal.revision(),
                        identity.revision()));
    }
}
