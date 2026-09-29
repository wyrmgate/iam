package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class EffectiveAccessQueryService implements EffectiveAccessQuery {

    private final EffectiveAccessRepository repository;

    public EffectiveAccessQueryService(EffectiveAccessRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Set<UUID> currentEntitlementIds(
            TenantContext tenant,
            UUID identityId,
            Set<UUID> entitlementIds,
            Instant at) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(entitlementIds, "entitlementIds");
        Objects.requireNonNull(at, "at");
        return Set.copyOf(repository.findCurrentEntitlementIds(
                tenant, identityId, entitlementIds, at));
    }

    @Override
    public Optional<Result> find(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey,
            Instant at) {
        Objects.requireNonNull(at, "at");
        return repository.findCurrent(
                        tenant, identityId, entitlementId, principalConstraintKey, at)
                .flatMap(effective -> {
                    var supports = repository.currentSupportingAssignmentIds(
                            tenant, effective.id(), at);
                    if (supports.isEmpty()) return Optional.empty();
                    var semantic = new io.wyrmgate.iam.access.domain.EffectiveAccess(
                            effective.id(),
                            effective.identityId(),
                            effective.entitlementId(),
                            effective.principalConstraintKey(),
                            supports.size(),
                            effective.computedAt(),
                            effective.projectionGeneration());
                    return Optional.of(new Result(semantic, supports));
                });
    }
}
