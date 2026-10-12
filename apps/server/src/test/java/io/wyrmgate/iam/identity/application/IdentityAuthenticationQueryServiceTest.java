package io.wyrmgate.iam.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.identity.domain.PrincipalKind;
import io.wyrmgate.iam.identity.domain.PrincipalLifecycleState;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityAuthenticationQueryServiceTest {

    private static final TenantContext TENANT = new TenantContext(UUID.randomUUID());
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final UUID PRINCIPAL_ID = UUID.randomUUID();
    private static final UUID IDENTITY_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-10T04:00:00Z");

    private final PrincipalRepository principals = mock(PrincipalRepository.class);
    private final IdentityRepository identities = mock(IdentityRepository.class);
    private final IdentityAuthenticationQueryService service =
            new IdentityAuthenticationQueryService(principals, identities);

    @Test
    void resolvesOnlyActiveCorrelatedPrincipalAndIdentity() {
        Principal principal = principal(PrincipalLifecycleState.ACTIVE, IDENTITY_ID, 7, "alice");
        Identity identity = identity(IdentityLifecycleState.ACTIVE, 11);
        when(principals.findByTargetAndNativeKey(TENANT, TARGET_ID, "alice"))
                .thenReturn(Optional.of(principal));
        when(identities.findById(TENANT, IDENTITY_ID)).thenReturn(Optional.of(identity));

        assertThat(service.resolveEligible(TENANT, TARGET_ID, " alice "))
                .contains(new IdentityAuthenticationQuery.AuthenticationSubject(
                        PRINCIPAL_ID, IDENTITY_ID, 7, 11));
    }

    @Test
    void inactiveOrUncorrelatedSubjectsFailClosed() {
        when(principals.findByTargetAndNativeKey(TENANT, TARGET_ID, "disabled"))
                .thenReturn(Optional.of(principal(
                        PrincipalLifecycleState.DISABLED, IDENTITY_ID, 1, "disabled")));
        when(principals.findByTargetAndNativeKey(TENANT, TARGET_ID, "uncorrelated"))
                .thenReturn(Optional.of(principal(
                        PrincipalLifecycleState.ACTIVE, null, 1, "uncorrelated")));
        when(principals.findByTargetAndNativeKey(TENANT, TARGET_ID, "inactive-identity"))
                .thenReturn(Optional.of(principal(
                        PrincipalLifecycleState.ACTIVE, IDENTITY_ID, 1, "inactive-identity")));
        when(identities.findById(TENANT, IDENTITY_ID))
                .thenReturn(Optional.of(identity(IdentityLifecycleState.SUSPENDED, 1)));

        assertThat(service.resolveEligible(TENANT, TARGET_ID, "disabled")).isEmpty();
        assertThat(service.resolveEligible(TENANT, TARGET_ID, "uncorrelated")).isEmpty();
        assertThat(service.resolveEligible(TENANT, TARGET_ID, "inactive-identity")).isEmpty();
        assertThat(service.resolveEligible(TENANT, TARGET_ID, " ")).isEmpty();
    }

    private static Principal principal(
            PrincipalLifecycleState state,
            UUID identityId,
            long revision,
            String nativeKey) {
        return new Principal(
                PRINCIPAL_ID,
                identityId,
                TARGET_ID,
                PrincipalKind.ACCOUNT,
                nativeKey,
                state,
                revision,
                NOW.minusSeconds(60),
                NOW);
    }

    private static Identity identity(IdentityLifecycleState state, long revision) {
        return new Identity(
                IDENTITY_ID,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                state,
                "Alice Example",
                revision,
                NOW.minusSeconds(60),
                NOW);
    }
}
