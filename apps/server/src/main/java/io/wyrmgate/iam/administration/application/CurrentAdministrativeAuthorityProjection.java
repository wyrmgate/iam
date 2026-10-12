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

    private static final int MAX_AUTHORITIES = 1_000;
    private static final int QUERY_LIMIT = MAX_AUTHORITIES + 1;

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
        var grants = repository.findGrantCandidates(actor.tenant(), actor.identityId(), QUERY_LIMIT);
        requireBounded(grants.size());
        for (var candidate : grants) {
            var grant = candidate.grant();
            if (grant.isEffectiveAt(now) && supportedScope(grant.scope().type())) {
                authorities.add(new EffectiveAdministrativeAuthority(
                        candidate.permission(), grant.scope(), AdministrativeAuthoritySource.DIRECT_GRANT,
                        grant.id(), grant.validFrom(), grant.validUntil()));
            }
        }

        var delegations = repository.findDelegationCandidates(actor.tenant(), actor.identityId(), QUERY_LIMIT);
        requireBounded(delegations.size());
        for (var candidate : delegations) {
            var authority = candidate.authority();
            var delegation = authority.delegation();
            if (isEffectiveDelegation(authority, now)
                    && supportedScope(delegation.scope().type())) {
                authorities.add(new EffectiveAdministrativeAuthority(
                        candidate.permission(), delegation.scope(), AdministrativeAuthoritySource.DELEGATION,
                        delegation.id(), delegation.validFrom(), delegation.validUntil()));
            }
        }

        var elevations = repository.findElevationCandidates(actor.tenant(), actor.identityId(), QUERY_LIMIT);
        requireBounded(elevations.size());
        for (var candidate : elevations) {
            var elevation = candidate.elevation();
            if (elevation.isEffectiveAt(now) && supportedScope(elevation.scope().type())) {
                authorities.add(new EffectiveAdministrativeAuthority(
                        candidate.permission(), elevation.scope(), AdministrativeAuthoritySource.ELEVATION,
                        elevation.id(), elevation.validFrom(), elevation.validUntil()));
            }
        }

        var breakGlass = repository.findBreakGlassCandidates(actor.tenant(), actor.identityId(), QUERY_LIMIT);
        requireBounded(breakGlass.size());
        for (var candidate : breakGlass) {
            var operation = candidate.operation();
            if (operation.isEffectiveAt(now)
                    && operation.currentAssuranceAllows(actor.assurance(), now)
                    && supportedScope(operation.scope().type())) {
                authorities.add(new EffectiveAdministrativeAuthority(
                        candidate.permission(), operation.scope(), AdministrativeAuthoritySource.BREAK_GLASS,
                        operation.id(), operation.validFrom(), operation.validUntil()));
            }
        }
        requireBounded(authorities.size());

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

    private static void requireBounded(int count) {
        if (count > MAX_AUTHORITIES) {
            throw new AdministrativeAuthorityException(
                    "authority_projection_unavailable",
                    "Current administrative authority exceeds the bounded synchronous projection capacity.");
        }
    }

    private static boolean isEffectiveDelegation(
            AdministrativeDelegatedAuthorityCandidate candidate, Instant now) {
        var delegation = candidate.delegation();
        var source = candidate.sourceGrant();
        if (!delegation.isEffectiveAt(now)
                || !source.isEffectiveAt(now)
                || !source.delegable()
                || !source.actorIdentityId().equals(delegation.delegatorIdentityId())
                || !source.id().equals(delegation.sourceGrantId())
                || !source.roleId().equals(delegation.roleId())
                || !AdministrativeAuthorityService.scopeContains(source.scope(), delegation.scope())) {
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
