package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class EffectiveAccessQueryService implements EffectiveAccessQuery {

    private final EffectiveAccessRepository repository;
    private final IdentityAccessReferenceQuery identities;
    private final EffectiveAccessSemanticSupport semanticSupport;

    public EffectiveAccessQueryService(
            EffectiveAccessRepository repository,
            IdentityAccessReferenceQuery identities,
            EffectiveAccessSemanticSupport semanticSupport) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.identities = Objects.requireNonNull(
                identities, "identities");
        this.semanticSupport = Objects.requireNonNull(
                semanticSupport, "semanticSupport");
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
        if (!accessEligible(tenant, identityId)) {
            return Set.of();
        }
        Set<UUID> projected = repository.findCurrentEntitlementIds(
                tenant, identityId, entitlementIds, at);
        java.util.LinkedHashSet<UUID> semantic = new java.util.LinkedHashSet<>();
        for (UUID entitlementId : projected) {
            boolean supported = repository.currentSupportingAssignmentIds(
                            tenant, identityId, entitlementId, at)
                    .stream()
                    .anyMatch(assignmentId -> semanticSupport.contributes(
                            tenant, assignmentId, entitlementId, at));
            if (supported) {
                semantic.add(entitlementId);
            }
        }
        return Set.copyOf(semantic);
    }

    @Override
    public Optional<Result> find(
            TenantContext tenant,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey,
            Instant at) {
        Objects.requireNonNull(at, "at");
        if (!accessEligible(tenant, identityId)) {
            return Optional.empty();
        }
        return repository.findCurrent(
                        tenant, identityId, entitlementId, principalConstraintKey, at)
                .flatMap(effective -> {
                    var supports = repository.currentSupportingAssignmentIds(
                                    tenant, effective.id(), at)
                            .stream()
                            .filter(assignmentId -> semanticSupport.contributes(
                                    tenant,
                                    assignmentId,
                                    effective.entitlementId(),
                                    at))
                            .toList();
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

    private boolean accessEligible(
            TenantContext tenant,
            UUID identityId) {
        return identities.accessStatus(
                        tenant, identityId)
                .status()
                == IdentityAccessReferenceQuery
                        .AccessStatus.ACCESS_ELIGIBLE;
    }
}
