package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeAuthoritySource;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Derives a point-in-time read projection of the authenticated actor's effective Administration
 * authority for console/navigation optimization.
 *
 * <p>The projection never replaces operation-time authorization. Every authoritative operation
 * must still call the normal Administration authorization evaluator.</p>
 */
public final class CurrentAdministrativeAuthorityProjection {

    private final AdministrativeAuthorityProjectionRepository repository;
    private final GovernedActorStatusQuery governedActorStatusQuery;

    public CurrentAdministrativeAuthorityProjection(
            AdministrativeAuthorityProjectionRepository repository,
            GovernedActorStatusQuery governedActorStatusQuery) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.governedActorStatusQuery = Objects.requireNonNull(
                governedActorStatusQuery, "governedActorStatusQuery");
    }

    public Result current(AuthenticatedAdministrativeActor actor, Instant now) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");

        boolean eligible = governedActorStatusQuery.isAdministrativelyEligible(
                actor.tenant(), actor.identityId());
        if (!eligible) {
            return new Result(false, List.of());
        }

        List<EffectiveAdministrativeAuthority> authorities = new ArrayList<>();
        for (var candidate : repository.findGrantCandidates(actor.tenant(), actor.identityId())) {
            var grant = candidate.grant();
            if (grant.isEffectiveAt(now) && supportedScope(grant.scope().type())) {
                authorities.add(new EffectiveAdministrativeAuthority(
                        candidate.permission(), grant.scope(), AdministrativeAuthoritySource.DIRECT_GRANT,
                        grant.id(), grant.validFrom(), grant.validUntil()));
            }
        }
        for (var candidate : repository.findDelegationCandidates(actor.tenant(), actor.identityId())) {
            var authority = candidate.authority();
            var delegation = authority.delegation();
            if (AdministrativeAuthorizationService.isEffectiveDelegation(authority, now)
                    && supportedScope(delegation.scope().type())) {
                authorities.add(new EffectiveAdministrativeAuthority(
                        candidate.permission(), delegation.scope(), AdministrativeAuthoritySource.DELEGATION,
                        delegation.id(), delegation.validFrom(), delegation.validUntil()));
            }
        }
        for (var candidate : repository.findElevationCandidates(actor.tenant(), actor.identityId())) {
            var elevation = candidate.elevation();
            if (elevation.isEffectiveAt(now) && supportedScope(elevation.scope().type())) {
                authorities.add(new EffectiveAdministrativeAuthority(
                        candidate.permission(), elevation.scope(), AdministrativeAuthoritySource.ELEVATION,
                        elevation.id(), elevation.validFrom(), elevation.validUntil()));
            }
        }
        for (var candidate : repository.findBreakGlassCandidates(actor.tenant(), actor.identityId())) {
            var operation = candidate.operation();
            if (operation.isEffectiveAt(now)
                    && operation.currentAssuranceAllows(actor.assurance(), now)
                    && supportedScope(operation.scope().type())) {
                authorities.add(new EffectiveAdministrativeAuthority(
                        candidate.permission(), operation.scope(), AdministrativeAuthoritySource.BREAK_GLASS,
                        operation.id(), operation.validFrom(), operation.validUntil()));
            }
        }

        authorities.sort(Comparator
                .comparing((EffectiveAdministrativeAuthority value) -> value.permission().key())
                .thenComparing(value -> value.scope().type().name())
                .thenComparing(value -> value.scope().resourceType(), Comparator.nullsFirst(String::compareTo))
                .thenComparing(value -> value.scope().resourceId(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(value -> value.scope().scopeKey(), Comparator.nullsFirst(String::compareTo))
                .thenComparing(value -> value.source().name())
                .thenComparing(EffectiveAdministrativeAuthority::sourceId));
        return new Result(true, List.copyOf(authorities));
    }

    private static boolean supportedScope(AdministrativeScopeType type) {
        return type == AdministrativeScopeType.GLOBAL
                || type == AdministrativeScopeType.SPECIFIC_RESOURCE
                || type == AdministrativeScopeType.CANONICAL_ATTRIBUTE_CLASSIFICATION;
    }

    public record Result(boolean administrativelyEligible, List<EffectiveAdministrativeAuthority> authorities) {
        public Result {
            authorities = List.copyOf(Objects.requireNonNull(authorities, "authorities"));
        }
    }
}
