package io.wyrmgate.iam.administration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrentAdministrativeAuthorityProjectionTest {

    private static final Instant NOW = Instant.parse("2026-10-10T07:00:00Z");

    @Test
    void suspensionInvalidatesProjectionBeforeAuthorityRowsAreRead() {
        var repository = mock(AdministrativeAuthorityProjectionRepository.class);
        var status = mock(GovernedActorStatusQuery.class);
        var actor = actor();
        when(status.isAdministrativelyEligible(actor.tenant(), actor.identityId())).thenReturn(false);

        var result = new CurrentAdministrativeAuthorityProjection(repository, status).current(actor, NOW);

        assertThat(result.administrativelyEligible()).isFalse();
        assertThat(result.authorities()).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void revokedAndExpiredDirectAuthorityDisappearImmediately() {
        var repository = mock(AdministrativeAuthorityProjectionRepository.class);
        var status = mock(GovernedActorStatusQuery.class);
        var actor = actor();
        var permission = new AdministrativePermission("identity", "read");
        when(status.isAdministrativelyEligible(actor.tenant(), actor.identityId())).thenReturn(true);
        when(repository.findGrantCandidates(actor.tenant(), actor.identityId(), anyInt())).thenReturn(List.of(
                candidate(permission, grant(actor, AdministrativeGrantState.ACTIVE, NOW.minusSeconds(60), NOW.plusSeconds(60))),
                candidate(permission, grant(actor, AdministrativeGrantState.REVOKED, NOW.minusSeconds(60), NOW.plusSeconds(60))),
                candidate(permission, grant(actor, AdministrativeGrantState.ACTIVE, NOW.minusSeconds(120), NOW))));
        when(repository.findDelegationCandidates(actor.tenant(), actor.identityId(), anyInt())).thenReturn(List.of());
        when(repository.findElevationCandidates(actor.tenant(), actor.identityId(), anyInt())).thenReturn(List.of());
        when(repository.findBreakGlassCandidates(actor.tenant(), actor.identityId(), anyInt())).thenReturn(List.of());

        var result = new CurrentAdministrativeAuthorityProjection(repository, status).current(actor, NOW);

        assertThat(result.authorities()).hasSize(1);
        assertThat(result.authorities().getFirst().permission()).isEqualTo(permission);
        assertThat(result.authorities().getFirst().sourceId()).isNotNull();
    }

    private static AdministrativeAuthorityProjectionRepository.GrantCandidate candidate(
            AdministrativePermission permission, AdministrativeGrant grant) {
        return new AdministrativeAuthorityProjectionRepository.GrantCandidate(permission, grant);
    }

    private static AdministrativeGrant grant(
            AuthenticatedAdministrativeActor actor,
            AdministrativeGrantState state,
            Instant validFrom,
            Instant validUntil) {
        Instant created = NOW.minusSeconds(300);
        return new AdministrativeGrant(
                UUID.randomUUID(), actor.identityId(), UUID.randomUUID(), AdministrativeScope.global(),
                state, validFrom, validUntil, false, false, null, 1, created, created);
    }

    private static AuthenticatedAdministrativeActor actor() {
        return new AuthenticatedAdministrativeActor(
                new TenantContext(UUID.randomUUID()), UUID.randomUUID());
    }
}
