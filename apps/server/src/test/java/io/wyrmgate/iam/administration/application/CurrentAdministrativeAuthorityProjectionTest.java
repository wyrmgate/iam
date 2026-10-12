package io.wyrmgate.iam.administration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.administration.domain.AdministrativeAuthoritySource;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeDelegation;
import io.wyrmgate.iam.administration.domain.AdministrativeElevation;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrentAdministrativeAuthorityProjectionTest {

    private static final Instant NOW = Instant.parse("2026-10-10T07:00:00Z");
    private static final AdministrativePermission PERMISSION =
            new AdministrativePermission("identity", "read");

    @Test
    void ineligibleActorReturnsEmptyBeforeReadingAuthority() {
        var repository = new FakeRepository();

        var result = projection(repository, false).current(actor(), NOW);

        assertThat(result.administrativelyEligible()).isFalse();
        assertThat(result.authorities()).isEmpty();
        assertThat(repository.readCount).isZero();
    }

    @Test
    void returnsOnlyEffectiveAuthorityFromEverySource() {
        var repository = new FakeRepository();
        var actor = actor();

        repository.grants = List.of(grant(true), grant(false));
        repository.delegations = List.of(delegation(true), delegation(false));
        repository.elevations = List.of(elevation(true), elevation(false));
        repository.breakGlass = List.of(breakGlass(true), breakGlass(false));

        var result = projection(repository, true).current(actor, NOW);

        assertThat(result.authorities())
                .extracting(EffectiveAdministrativeAuthority::source)
                .containsExactlyInAnyOrder(
                        AdministrativeAuthoritySource.DIRECT_GRANT,
                        AdministrativeAuthoritySource.DELEGATION,
                        AdministrativeAuthoritySource.ELEVATION,
                        AdministrativeAuthoritySource.BREAK_GLASS);
    }

    @Test
    void failsClosedWhenProjectionExceedsBound() {
        var repository = new FakeRepository();
        repository.grants = Collections.nCopies(1_001, grant(true));

        assertThatThrownBy(() -> projection(repository, true).current(actor(), NOW))
                .isInstanceOfSatisfying(
                        AdministrativeAuthorityException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("authority_projection_unavailable"));
    }

    private static CurrentAdministrativeAuthorityProjection projection(
            FakeRepository repository, boolean eligible) {
        return new CurrentAdministrativeAuthorityProjection(repository, (tenant, identityId) -> eligible);
    }

    private static AdministrativeAuthorityProjectionRepository.GrantCandidate grant(boolean effective) {
        var grant = mock(AdministrativeGrant.class);
        when(grant.isEffectiveAt(NOW)).thenReturn(effective);
        when(grant.scope()).thenReturn(AdministrativeScope.global());
        when(grant.id()).thenReturn(UUID.randomUUID());
        return new AdministrativeAuthorityProjectionRepository.GrantCandidate(PERMISSION, grant);
    }

    private static AdministrativeAuthorityProjectionRepository.DelegationCandidate delegation(boolean effective) {
        var delegation = mock(AdministrativeDelegation.class);
        var source = mock(AdministrativeGrant.class);
        UUID sourceId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID delegatorId = UUID.randomUUID();

        when(delegation.isEffectiveAt(NOW)).thenReturn(effective);
        when(delegation.scope()).thenReturn(AdministrativeScope.global());
        when(delegation.id()).thenReturn(UUID.randomUUID());
        when(delegation.delegatorIdentityId()).thenReturn(delegatorId);
        when(delegation.sourceGrantId()).thenReturn(sourceId);
        when(delegation.roleId()).thenReturn(roleId);
        when(delegation.validUntil()).thenReturn(NOW.plusSeconds(60));

        when(source.isEffectiveAt(NOW)).thenReturn(true);
        when(source.delegable()).thenReturn(true);
        when(source.actorIdentityId()).thenReturn(delegatorId);
        when(source.id()).thenReturn(sourceId);
        when(source.roleId()).thenReturn(roleId);
        when(source.scope()).thenReturn(AdministrativeScope.global());

        return new AdministrativeAuthorityProjectionRepository.DelegationCandidate(
                PERMISSION, new AdministrativeDelegatedAuthorityCandidate(delegation, source));
    }

    private static AdministrativeAuthorityProjectionRepository.ElevationCandidate elevation(boolean effective) {
        var elevation = mock(AdministrativeElevation.class);
        when(elevation.isEffectiveAt(NOW)).thenReturn(effective);
        when(elevation.scope()).thenReturn(AdministrativeScope.global());
        when(elevation.id()).thenReturn(UUID.randomUUID());
        return new AdministrativeAuthorityProjectionRepository.ElevationCandidate(PERMISSION, elevation);
    }

    private static AdministrativeAuthorityProjectionRepository.BreakGlassCandidate breakGlass(boolean effective) {
        var operation = mock(AdministrativeBreakGlassOperation.class);
        when(operation.isEffectiveAt(NOW)).thenReturn(effective);
        when(operation.currentAssuranceAllows(actor().assurance(), NOW)).thenReturn(true);
        when(operation.scope()).thenReturn(AdministrativeScope.global());
        when(operation.id()).thenReturn(UUID.randomUUID());
        return new AdministrativeAuthorityProjectionRepository.BreakGlassCandidate(PERMISSION, operation);
    }

    private static AuthenticatedAdministrativeActor actor() {
        return new AuthenticatedAdministrativeActor(
                new TenantContext(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                UUID.fromString("00000000-0000-0000-0000-000000000002"));
    }

    private static final class FakeRepository implements AdministrativeAuthorityProjectionRepository {
        private List<GrantCandidate> grants = List.of();
        private List<DelegationCandidate> delegations = List.of();
        private List<ElevationCandidate> elevations = List.of();
        private List<BreakGlassCandidate> breakGlass = List.of();
        private int readCount;

        @Override
        public List<GrantCandidate> findGrantCandidates(TenantContext tenant, UUID actorIdentityId, int limit) {
            readCount++;
            return grants;
        }

        @Override
        public List<DelegationCandidate> findDelegationCandidates(TenantContext tenant, UUID actorIdentityId, int limit) {
            readCount++;
            return delegations;
        }

        @Override
        public List<ElevationCandidate> findElevationCandidates(TenantContext tenant, UUID actorIdentityId, int limit) {
            readCount++;
            return elevations;
        }

        @Override
        public List<BreakGlassCandidate> findBreakGlassCandidates(TenantContext tenant, UUID actorIdentityId, int limit) {
            readCount++;
            return breakGlass;
        }
    }
}
