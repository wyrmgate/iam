package io.wyrmgate.iam.identity.application;

import io.wyrmgate.iam.identity.application.PrincipalQueryModels.PrincipalPage;
import io.wyrmgate.iam.identity.application.PrincipalQueryModels.PrincipalPosition;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PrincipalQueryService {

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
        return list(tenant, null, position, limit);
    }

    public PrincipalPage list(
            TenantContext tenant,
            UUID identityId,
            PrincipalPosition position,
            int limit) {
        Objects.requireNonNull(tenant, "tenant");
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        UUID afterId = position == null ? null : position.id();
        var rows = identityId == null
                ? repository.listAfterId(tenant, afterId, limit + 1)
                : repository.listByIdentityAfterId(tenant, identityId, afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        var items = hasMore ? rows.subList(0, limit) : rows;
        PrincipalPosition next = hasMore
                ? new PrincipalPosition(items.get(items.size() - 1).id())
                : null;
        return new PrincipalPage(items, next);
    }

}
