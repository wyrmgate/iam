package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import io.wyrmgate.iam.identity.application.PrincipalQueryModels.PrincipalPage;
import io.wyrmgate.iam.identity.application.PrincipalQueryModels.PrincipalPosition;
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

    public PrincipalPage list(
            TenantContext tenant,
            PrincipalPosition position,
            int limit) {
        Objects.requireNonNull(tenant, "tenant");
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        UUID afterId = position == null ? null : position.id();
        var rows = repository.listAfterId(tenant, afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        var items = hasMore ? rows.subList(0, limit) : rows;
        PrincipalPosition next = hasMore
                ? new PrincipalPosition(items.get(items.size() - 1).id())
                : null;
        return new PrincipalPage(items, next);
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
