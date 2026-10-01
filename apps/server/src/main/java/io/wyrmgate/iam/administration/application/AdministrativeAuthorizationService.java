package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeAuthoritySource;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Default-deny evaluator for direct, delegated, elevated and emergency administrative authority. */
public final class AdministrativeAuthorizationService {

    private final AdministrativeAuthorizationRepository repository;
    private final GovernedActorStatusQuery governedActorStatusQuery;

    public AdministrativeAuthorizationService(
            AdministrativeAuthorizationRepository repository,
            GovernedActorStatusQuery governedActorStatusQuery) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.governedActorStatusQuery = Objects.requireNonNull(
                governedActorStatusQuery, "governedActorStatusQuery");
    }

    public AdministrativeAuthorizationDecision authorize(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(now, "now");

        if (!permission.resourceType().equals(resource.resourceType())) {
            return AdministrativeAuthorizationDecision.deny("resource_type_mismatch");
        }
        if (!governedActorStatusQuery.isAdministrativelyEligible(
                actor.tenant(), actor.identityId())) {
            return AdministrativeAuthorizationDecision.deny("actor_not_eligible");
        }

        for (AdministrativeGrant grant : repository.findCandidateGrants(
                actor.tenant(), actor.identityId(), permission)) {
            if (grant.isEffectiveAt(now) && matches(grant.scope(), resource)) {
                return AdministrativeAuthorizationDecision.allow(
                        AdministrativeAuthoritySource.DIRECT_GRANT);
            }
        }
        for (AdministrativeDelegatedAuthorityCandidate candidate
                : repository.findCandidateDelegations(
                        actor.tenant(), actor.identityId(), permission)) {
            if (isEffectiveDelegation(candidate, now)
                    && matches(candidate.delegation().scope(), resource)) {
                return AdministrativeAuthorizationDecision.allow(
                        AdministrativeAuthoritySource.DELEGATION);
            }
        }
        for (var elevation : repository.findCandidateElevations(
                actor.tenant(), actor.identityId(), permission)) {
            if (elevation.isEffectiveAt(now)
                    && matches(elevation.scope(), resource)) {
                return AdministrativeAuthorizationDecision.allow(
                        AdministrativeAuthoritySource.ELEVATION);
            }
        }
        for (var breakGlass : repository.findCandidateBreakGlassOperations(
                actor.tenant(), actor.identityId(), permission)) {
            if (breakGlass.isEffectiveAt(now)
                    && breakGlass.currentAssuranceAllows(actor.assurance(), now)
                    && matches(breakGlass.scope(), resource)) {
                return AdministrativeAuthorizationDecision.allow(
                        AdministrativeAuthoritySource.BREAK_GLASS);
            }
        }
        return AdministrativeAuthorizationDecision.deny("no_effective_grant");
    }

    public Set<String> authorizedClassificationKeys(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            Set<String> requestedClassificationKeys,
            Instant now) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(requestedClassificationKeys, "requestedClassificationKeys");
        Objects.requireNonNull(now, "now");
        if (!governedActorStatusQuery.isAdministrativelyEligible(
                actor.tenant(), actor.identityId())) {
            return Set.of();
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String key : requestedClassificationKeys) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        "classification key must not be blank");
            }
            requested.add(key);
        }
        if (requested.isEmpty()) {
            return Set.of();
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (AdministrativeGrant grant : repository.findCandidateGrants(
                actor.tenant(), actor.identityId(), permission)) {
            if (!grant.isEffectiveAt(now)) continue;
            if (collectClassificationScope(grant.scope(), requested, allowed)) {
                return Set.copyOf(requested);
            }
        }
        for (AdministrativeDelegatedAuthorityCandidate candidate
                : repository.findCandidateDelegations(
                        actor.tenant(), actor.identityId(), permission)) {
            if (!isEffectiveDelegation(candidate, now)) continue;
            if (collectClassificationScope(
                    candidate.delegation().scope(), requested, allowed)) {
                return Set.copyOf(requested);
            }
        }
        for (var elevation : repository.findCandidateElevations(
                actor.tenant(), actor.identityId(), permission)) {
            if (!elevation.isEffectiveAt(now)) continue;
            if (collectClassificationScope(
                    elevation.scope(), requested, allowed)) {
                return Set.copyOf(requested);
            }
        }
        for (var breakGlass : repository.findCandidateBreakGlassOperations(
                actor.tenant(), actor.identityId(), permission)) {
            if (!breakGlass.isEffectiveAt(now)
                    || !breakGlass.currentAssuranceAllows(actor.assurance(), now)) {
                continue;
            }
            if (collectClassificationScope(
                    breakGlass.scope(), requested, allowed)) {
                return Set.copyOf(requested);
            }
        }
        return Set.copyOf(allowed);
    }

    private static boolean collectClassificationScope(
            io.wyrmgate.iam.administration.domain.AdministrativeScope scope,
            Set<String> requested,
            Set<String> allowed) {
        if (scope.type() == AdministrativeScopeType.GLOBAL) {
            return true;
        }
        if (scope.type()
                        == AdministrativeScopeType.CANONICAL_ATTRIBUTE_CLASSIFICATION
                && requested.contains(scope.scopeKey())) {
            allowed.add(scope.scopeKey());
        }
        return false;
    }

    private static boolean isEffectiveDelegation(
            AdministrativeDelegatedAuthorityCandidate candidate,
            Instant now) {
        var delegation = candidate.delegation();
        var source = candidate.sourceGrant();
        if (!delegation.isEffectiveAt(now)
                || !source.isEffectiveAt(now)
                || !source.delegable()
                || !source.actorIdentityId().equals(delegation.delegatorIdentityId())
                || !source.id().equals(delegation.sourceGrantId())
                || !source.roleId().equals(delegation.roleId())
                || !AdministrativeAuthorityService.scopeContains(
                        source.scope(), delegation.scope())) {
            return false;
        }
        if (source.validFrom() != null
                && delegation.validFrom() != null
                && delegation.validFrom().isBefore(source.validFrom())) {
            return false;
        }
        return source.validUntil() == null
                || !delegation.validUntil().isAfter(source.validUntil());
    }

    private static boolean matches(
            io.wyrmgate.iam.administration.domain.AdministrativeScope scope,
            AdministrativeResource resource) {
        if (scope.type() == AdministrativeScopeType.GLOBAL) {
            return true;
        }
        if (scope.type() == AdministrativeScopeType.SPECIFIC_RESOURCE) {
            return resource.resourceId() != null
                    && scope.resourceType().equals(resource.resourceType())
                    && scope.resourceId().equals(resource.resourceId());
        }
        // Other canonical scope kinds are modeled but remain fail-closed until their
        // owning resource hierarchy/population semantics have concrete evaluators.
        return false;
    }
}
