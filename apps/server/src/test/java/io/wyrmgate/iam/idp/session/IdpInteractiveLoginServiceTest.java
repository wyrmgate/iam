package io.wyrmgate.iam.idp.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.catalog.domain.SsoClientLifecycleState;
import io.wyrmgate.iam.catalog.domain.SsoClientRegistration;
import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.credential.application.CredentialAuthenticationService;
import io.wyrmgate.iam.credential.application.CredentialAuthenticationService.VerifiedCredential;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery;
import io.wyrmgate.iam.identity.application.IdentityAuthenticationQuery.AuthenticationSubject;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdpInteractiveLoginServiceTest {

    @Test
    void derivesTenantFromActiveClientAndEstablishesFreshSessionAfterCredentialProof() {
        SsoClientProtocolQuery clients = mock(SsoClientProtocolQuery.class);
        IdentityAuthenticationQuery identities = mock(IdentityAuthenticationQuery.class);
        CredentialAuthenticationService credentials = mock(CredentialAuthenticationService.class);
        IdpBrowserSessionService sessions = mock(IdpBrowserSessionService.class);
        TenantContext tenant = new TenantContext(UUID.randomUUID());
        UUID targetId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-10T03:40:00Z");
        SsoClientRegistration registration = new SsoClientRegistration(
                UUID.randomUUID(), UUID.randomUUID(), "client-1",
                Set.of("https://console.example.test/callback"),
                Set.of(SsoClientScope.OPENID), false, SsoClientLifecycleState.ACTIVE,
                1, now.minusSeconds(60), now.minusSeconds(60));
        AuthenticationSubject subject = new AuthenticationSubject(principalId, identityId, 1, 1);
        VerifiedCredential verified = new VerifiedCredential(principalId, credentialId, 2);
        IdpBrowserSessionService.EstablishedSession established = mock(IdpBrowserSessionService.EstablishedSession.class);

        when(clients.resolveActive("client-1"))
                .thenReturn(Optional.of(new SsoClientProtocolQuery.ResolvedClient(tenant, registration)));
        when(identities.resolveEligible(tenant, targetId, "alice"))
                .thenReturn(Optional.of(subject));
        when(credentials.verifyPassword(eq(tenant), eq(principalId), any(char[].class), eq(now)))
                .thenReturn(Optional.of(verified));
        when(sessions.establish(tenant, subject, verified, now)).thenReturn(established);

        IdpInteractiveLoginService service = new IdpInteractiveLoginService(
                clients, identities, credentials, sessions);

        assertThat(service.authenticate(
                "client-1", targetId.toString(), "alice", "correct horse".toCharArray(), now))
                .contains(established);
        verify(sessions).establish(tenant, subject, verified, now);
    }

    @Test
    void invalidClientOrTargetFailsWithoutCredentialVerification() {
        SsoClientProtocolQuery clients = mock(SsoClientProtocolQuery.class);
        IdentityAuthenticationQuery identities = mock(IdentityAuthenticationQuery.class);
        CredentialAuthenticationService credentials = mock(CredentialAuthenticationService.class);
        IdpBrowserSessionService sessions = mock(IdpBrowserSessionService.class);
        when(clients.resolveActive("unknown")).thenReturn(Optional.empty());
        IdpInteractiveLoginService service = new IdpInteractiveLoginService(
                clients, identities, credentials, sessions);

        assertThat(service.authenticate(
                "unknown", "not-a-uuid", "alice", "secret".toCharArray(), Instant.now()))
                .isEmpty();
    }
}
