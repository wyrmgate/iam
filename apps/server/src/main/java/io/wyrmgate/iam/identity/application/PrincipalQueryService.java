package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PrincipalQueryService implements PrincipalResolutionQuery {

    private final PrincipalRepository repository;

    public PrincipalQueryService(PrincipalRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Optional<Principal> findById(TenantContext tenant, UUID principalId) {
        return repository.findById(tenant, principalId);
    }

    @Override
    public Resolution resolve(
            TenantContext tenant,
            UUID applicationTargetId,
            String nativePrincipalKey) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(applicationTargetId, "applicationTargetId");
        if (nativePrincipalKey == null || nativePrincipalKey.isBlank()) {
            throw new IllegalArgumentException("nativePrincipalKey must not be blank");
        }

        return repository.findByTargetAndNativeKey(
                        tenant, applicationTargetId, nativePrincipalKey.trim())
                .map(principal -> principal.identityId() == null
                        ? Resolution.uncorrelated(principal.id())
                        : Resolution.resolved(principal.id(), principal.identityId()))
                .orElseGet(Resolution::notFound);
    }
}
