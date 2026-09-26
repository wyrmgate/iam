package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveDetail;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectivePage;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectivePosition;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class EffectiveAccessReadService {

    private final EffectiveAccessRepository repository;

    public EffectiveAccessReadService(
            EffectiveAccessRepository repository) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
    }

    public EffectivePage list(
            TenantContext tenant,
            UUID identityId,
            EffectivePosition after,
            int limit,
            Instant at) {
        requireLimit(limit);
        Objects.requireNonNull(at, "at");
        List<EffectiveAccess> fetched =
                repository.findCurrentPage(
                        tenant,
                        identityId,
                        after == null ? null : after.id(),
                        limit + 1,
                        at);
        boolean more = fetched.size() > limit;
        List<EffectiveAccess> selected = fetched.subList(
                0, Math.min(limit, fetched.size()));
        List<EffectiveAccess> semantic = new ArrayList<>();
        for (EffectiveAccess value : selected) {
            var supports = repository.currentSupportEvidence(
                    tenant, value.id(), at);
            if (!supports.isEmpty()) {
                semantic.add(withSupportCount(
                        value, supports.size()));
            }
        }
        EffectivePosition next = more && !selected.isEmpty()
                ? new EffectivePosition(selected.getLast().id())
                : null;
        return new EffectivePage(
                List.copyOf(semantic), next);
    }

    public Optional<EffectiveDetail> find(
            TenantContext tenant,
            UUID effectiveAccessId,
            Instant at) {
        Objects.requireNonNull(at, "at");
        return repository.findCurrentById(
                        tenant, effectiveAccessId, at)
                .flatMap(value -> {
                    var supports =
                            repository.currentSupportEvidence(
                                    tenant,
                                    effectiveAccessId,
                                    at);
                    if (supports.isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(new EffectiveDetail(
                            withSupportCount(
                                    value, supports.size()),
                            supports));
                });
    }

    private static EffectiveAccess withSupportCount(
            EffectiveAccess value, int supportCount) {
        return new EffectiveAccess(
                value.id(),
                value.identityId(),
                value.entitlementId(),
                value.principalConstraintKey(),
                supportCount,
                value.computedAt(),
                value.projectionGeneration());
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 200");
        }
    }
}
