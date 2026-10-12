package io.wyrmgate.iam.administration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.administration.domain.AdministrativeAuthoritySource;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassState;
import io.wyrmgate.iam.administration.domain.AdministrativeDelegation;
import io.wyrmgate.iam.administration.domain.AdministrativeDelegationState;
import io.wyrmgate.iam.administration.domain.AdministrativeElevation;
import io.wyrmgate.iam.administration.domain.AdministrativeElevationState;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceContext;
import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceLevel;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrentAdministrativeAuthorityProjectionTest {

    private static final Instant NOW = Instant.parse("2026-10-10T07:00:00Z");
    private static final Instant CREATED = NOW.minusSeconds(600);
    private static final AdministrativePermission PERMISSION =
            new AdministrativePermission("identity", "read");

    @Test
    void ineligibleActorReturnsEmptyWithoutReadingAuthority() {
        var repository = new FakeProjectionRepository();

        var result = projection(repository, false).current(strongActor(), NOW);

        assertThat(result.administrativelyEligible()).isFalse();
        assertThat(result.authorities()).isEmpty();
        assertThat(repository.readCount).isZero();
    }

    @Test
    void returnsOnlyCurrentSupportedAuthorityFromEverySource() {
        var repository = new FakeProjectionRepository();
        var actor = strongActor();
        var unsupportedScope = new AdministrativeScope(
                AdministrativeScopeType.ORGANIZATION, null, UUID.randomUUID(), null);

        repository.grants = List.of(
                direct(actor, AdministrativeScope.global(), AdministrativeGrantState.ACTIVE,
                        NOW.minusSeconds(60), NOW.plusSeconds(60)),
                direct(actor, AdministrativeScope.global(), AdministrativeGrantState.REVOKED,
                        NOW.minusSeconds(60), NOW.plusSeconds(60)),
                direct(actor, AdministrativeScope.global(), AdministrativeGrantState.ACTIVE,
                        NOW.minusSeconds(120), NOW),
                direct(actor, unsupportedScope, AdministrativeGrantState.ACTIVE,
                        NOW.minusSeconds(60), NOW.plusSeconds(60)));
        repository.delegations = List.of(
                delegation(actor, AdministrativeGrantState.ACTIVE, true),
                delegation(actor, AdministrativeGrantState.REVOKED, true),
                delegation(actor, AdministrativeGrantState.ACTIVE, false));
        repository.elevations = List.of(
                elevation(actor, AdministrativeScope.canonicalAttributeClassification("HR"),
                        NOW.minusSeconds(60), NOW.plusSeconds(60)),
                elevation(actor, AdministrativeScope.global(), NOW.minusSeconds(120), NOW));
        repository.breakGlass = List.of(
                breakGlass(actor, NOW.minusSeconds(60), NOW.plusSeconds(60)),
                breakGlass(actor, NOW.minusSeconds(120), NOW));

        var result = projection(repository, true).current(actor, NOW);

        assertThat(result.administrativelyEligible()).isTrue();
        assertThat(result.authorities())
                .extracting(EffectiveAdministrativeAuthority::source)
                .containsExactly(
                        AdministrativeAuthoritySource.ELEVATION,
                        AdministrativeAuthoritySource.BREAK_GLASS,
                        AdministrativeAuthoritySource.DIRECT_GRANT,
                        AdministrativeAuthoritySource.DELEGATION);
        assertThat(result.authorities())
                .extracting(authority -> authority.scope().type())
                .containsExactly(
                        AdministrativeScopeType.CANONICAL_ATTRIBUTE_CLASSIFICATION,
                        AdministrativeScopeType.GLOBAL,
                        AdministrativeScopeType.GLOBAL,
                        AdministrativeScopeType.SPECIFIC_RESOURCE);
    }

    @Test
    void breakGlassRequiresCurrentStrongAssurance() {
        var repository = new FakeProjectionRepository();
        var strongActor = strongActor();
        repository.breakGlass = List.of(breakGlass(
                strongActor, NOW.minusSeconds(60), NOW.plusSeconds(60)));
        var baselineActor = new AuthenticatedAdministrativeActor(
                strongActor.tenant(), strongActor.identityId());

        var result = projection(repository, true).current(baselineActor, NOW);

        assertThat(result.authorities()).isEmpty();
    }

    @Test
    void failsClosedWhenOneSourceExceedsTheQueryBound() {
        var repository = new FakeProjectionRepository();
        var actor = strongActor();
        var candidate = direct(
                actor,
                AdministrativeScope.global(),
                AdministrativeGrantState.ACTIVE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(60));
        repository.grants = Collections.nCopies(1_001, candidate);

        assertProjectionUnavailable(repository, actor);
    }

    @Test
    void failsClosedWhenCombinedEffectiveAuthorityExceedsTheProjectionBound() {
        var repository = new FakeProjectionRepository();
        var actor = strongActor();
        repository.grants = Collections.nCopies(
                501,
                direct(
                        actor,
                        AdministrativeScope.global(),
                        AdministrativeGrantState.ACTIVE,
                        NOW.minusSeconds(60),
                        NOW.plusSeconds(60)));
        repository.elevations = Collections.nCopies(
                500,
                elevation(actor, AdministrativeScope.global(), NOW.minusSeconds(60), NOW.plusSeconds(60)));

        assertProjectionUnavailable(repository, actor);
    }

    private static void assertProjectionUnavailable(
            FakeProjectionRepository repository, AuthenticatedAdministrativeActor actor) {
        assertThatThrownBy(() -> projection(repository, true).current(actor, NOW))
                .isInstanceOfSatisfying(
                        AdministrativeAuthorityException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("authority_projection_unavailable"));
    }

    private static CurrentAdministrativeAuthorityProjection projection(
            FakeProjectionRepository repository, boolean eligible) {
        return new CurrentAdministrativeAuthorityProjection(repository, (tenant, identityId) -> eligible);
    }

    private static AdministrativeAuthorityProjectionRepository.GrantCandidate direct(
            AuthenticatedAdministrativeActor actor,
            AdministrativeScope scope,
            AdministrativeGrantState state,
            Instant validFrom,
            Instant validUntil) {
        return new AdministrativeAuthorityProjectionRepository.GrantCandidate(
                PERMISSION,
                new AdministrativeGrant(
                        UUID.randomUUID(),
                        actor.identityId(),
                        UUID.randomUUID(),
                        scope,
                        state,
                        validFrom,
                        validUntil,
                        false,
                        false,
                        null,
                        1,
                        CREATED,
                        CREATED));
    }

    private static AdministrativeAuthorityProjectionRepository.DelegationCandidate delegation(
            AuthenticatedAdministrativeActor actor,
            AdministrativeGrantState sourceState,
            boolean sourceDelegable) {
        UUID delegatorId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        var source = new AdministrativeGrant(
                UUID.randomUUID(),
                delegatorId,
                roleId,
                AdministrativeScope.global(),
                sourceState,
                NOW.minusSeconds(120),
                NOW.plusSeconds(120),
                false,
                sourceDelegable,
                null,
                1,
                CREATED,
                CREATED);
        var delegatedScope = AdministrativeScope.specificResource("identity", UUID.randomUUID());
        var delegated = new AdministrativeDelegation(
                UUID.randomUUID(),
                actor.identityId(),
                delegatorId,
                source.id(),
                roleId,
                delegatedScope,
                AdministrativeDelegationState.ACTIVE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(60),
                delegatorId,
                null,
                null,
                null,
                null,
                1,
                CREATED,
                CREATED);
        return new AdministrativeAuthorityProjectionRepository.DelegationCandidate(
                PERMISSION, new AdministrativeDelegatedAuthorityCandidate(delegated, source));
    }

    private static AdministrativeAuthorityProjectionRepository.ElevationCandidate elevation(
            AuthenticatedAdministrativeActor actor,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil) {
        var elevation = new AdministrativeElevation(
                UUID.randomUUID(),
                actor.identityId(),
                actor.identityId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                scope,
                validFrom,
                validUntil,
                "test-fingerprint",
                AdministrativeElevationState.ACTIVE,
                null,
                null,
                validFrom,
                null,
                null,
                null,
                null,
                null,
                1,
                CREATED,
                CREATED);
        return new AdministrativeAuthorityProjectionRepository.ElevationCandidate(PERMISSION, elevation);
    }

    private static AdministrativeAuthorityProjectionRepository.BreakGlassCandidate breakGlass(
            AuthenticatedAdministrativeActor actor,
            Instant validFrom,
            Instant validUntil) {
        var operation = new AdministrativeBreakGlassOperation(
                UUID.randomUUID(),
                actor.identityId(),
                UUID.randomUUID(),
                AdministrativeScope.global(),
                "test reason",
                "INC-TEST",
                validFrom,
                validUntil,
                AuthenticationAssuranceLevel.STRONG,
                NOW.minusSeconds(120),
                NOW.minusSeconds(60),
                300,
                AdministrativeBreakGlassState.ACTIVE,
                validFrom,
                null,
                null,
                null,
                null,
                1,
                CREATED,
                CREATED);
        return new AdministrativeAuthorityProjectionRepository.BreakGlassCandidate(PERMISSION, operation);
    }

    private static AuthenticatedAdministrativeActor strongActor() {
        return new AuthenticatedAdministrativeActor(
                new TenantContext(UUID.randomUUID()),
                UUID.randomUUID(),
                AuthenticationAssuranceContext.strong(NOW.minusSeconds(120), NOW.minusSeconds(60)));
    }

    private static final class FakeProjectionRepository
            implements AdministrativeAuthorityProjectionRepository {
        private List<GrantCandidate> grants = List.of();
        private List<DelegationCandidate> delegations = List.of();
        private List<ElevationCandidate> elevations = List.of();
        private List<BreakGlassCandidate> breakGlass = List.of();
        private int readCount;

        @Override
        public List<GrantCandidate> findGrantCandidates(
                TenantContext tenant, UUID actorIdentityId, int limit) {
            readCount++;
            return grants;
        }

        @Override
        public List<DelegationCandidate> findDelegationCandidates(
                TenantContext tenant, UUID actorIdentityId, int limit) {
            readCount++;
            return delegations;
        }

        @Override
        public List<ElevationCandidate> findElevationCandidates(
                TenantContext tenant, UUID actorIdentityId, int limit) {
            readCount++;
            return elevations;
        }

        @Override
        public List<BreakGlassCandidate> findBreakGlassCandidates(
                TenantContext tenant, UUID actorIdentityId, int limit) {
            readCount++;
            return breakGlass;
        }
    }
}
