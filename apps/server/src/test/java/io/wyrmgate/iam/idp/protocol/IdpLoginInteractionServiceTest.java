package io.wyrmgate.iam.idp.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.catalog.domain.SsoClientLifecycleState;
import io.wyrmgate.iam.catalog.domain.SsoClientRegistration;
import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService;
import io.wyrmgate.iam.idp.session.IdpCsrfTokenCodec;
import io.wyrmgate.iam.idp.session.IdpInteractiveLoginService;
import io.wyrmgate.iam.idp.session.IdpSessionTokenCodec;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdpLoginInteractionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-15T08:00:00Z");
    private static final String REDIRECT = "https://client.example/callback";
    private static final String STATE = "opaque-state+abcdefghijkl";
    private final TenantContext tenant = new TenantContext(UUID.randomUUID());
    private final UUID target = UUID.randomUUID();
    private final UUID registrationId = UUID.randomUUID();
    private final UUID app = UUID.randomUUID();
    private final SsoClientProtocolQuery clients = mock(SsoClientProtocolQuery.class);
    private final IdpBrowserSessionService sessions = mock(IdpBrowserSessionService.class);
    private final IdpInteractiveLoginService login = mock(IdpInteractiveLoginService.class);
    private final IdpAuthorizationCodeRepository codes = mock(IdpAuthorizationCodeRepository.class);
    private final MemoryInteractions repo = new MemoryInteractions();
    private final IdpProtocolService protocol = new IdpProtocolService(clients, sessions,
            (t, i, a, at) -> true, codes, mock(IdpTokenIssuer.class), UUID::randomUUID);
    private final IdpLoginInteractionService interactions = new IdpLoginInteractionService(
            protocol, clients, login, sessions, new IdpSessionTokenCodec(), repo);

    private SsoClientProtocolQuery.ResolvedClient client(long revision) {
        return new SsoClientProtocolQuery.ResolvedClient(tenant,
                new SsoClientRegistration(registrationId, app, "client", target, Set.of(REDIRECT),
                        Set.of(SsoClientScope.OPENID), true, SsoClientLifecycleState.ACTIVE,
                        revision, NOW.minusSeconds(60), NOW));
    }

    private IdpProtocolService.AuthorizationRequest request() {
        return new IdpProtocolService.AuthorizationRequest("code", "client", REDIRECT, "openid",
                STATE, "a".repeat(43), "S256", null);
    }

    private void configure(long revision) {
        var client = client(revision);
        when(clients.resolveActive("client")).thenReturn(Optional.of(client));
        when(clients.resolveAuthenticationTarget(client)).thenReturn(Optional.of(target));
    }

    @Test void browserBindingAndReplayAndChangedRegistrationFailClosed() {
        configure(1);
        var start = interactions.begin(protocol.validate(request()), NOW);
        assertThat(repo.value.formHash()).doesNotContain(start.formProof());
        assertThat(repo.value.browserHash()).doesNotContain(start.browserProof());
        assertThatThrownBy(() -> interactions.complete(start.formProof(), "x".repeat(43), "alice",
                "secret".toCharArray(), NOW.plusSeconds(1))).isInstanceOf(IdpProtocolService.ProtocolException.class);
        configure(2);
        assertThatThrownBy(() -> interactions.complete(start.formProof(), start.browserProof(), "alice",
                "secret".toCharArray(), NOW.plusSeconds(1))).isInstanceOf(IdpProtocolService.ProtocolException.class);
        assertThatThrownBy(() -> interactions.complete(start.formProof(), start.browserProof(), "alice",
                "secret".toCharArray(), NOW.plusSeconds(2))).isInstanceOf(IdpProtocolService.ProtocolException.class);
        verifyNoInteractions(login, codes);
    }

    @Test void successfulContinuationIssuesOneCodeAndNeverRestoresConsumedInteraction() {
        configure(1);
        var start = interactions.begin(protocol.validate(request()), NOW);
        var established = mock(IdpBrowserSessionService.EstablishedSession.class);
        when(established.token()).thenReturn("fresh-browser-token");
        when(login.authenticate(eq("client"), eq(target), eq("alice"), any(char[].class), eq(NOW.plusSeconds(1))))
                .thenReturn(Optional.of(established));
        when(sessions.resolve(tenant, "fresh-browser-token", NOW.plusSeconds(1))).thenReturn(Optional.of(
                new IdpBrowserSessionService.SessionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        NOW.plusSeconds(1), NOW.plusSeconds(600), NOW.plusSeconds(1200))));
        var completed = interactions.complete(start.formProof(), start.browserProof(), "alice",
                "secret".toCharArray(), NOW.plusSeconds(1));
        assertThat(completed.result().redirectUri()).isEqualTo(REDIRECT);
        assertThat(completed.result().state()).isEqualTo(STATE);
        verify(codes).insert(eq(tenant), any(IdpAuthorizationCode.class));
        assertThatThrownBy(() -> interactions.complete(start.formProof(), start.browserProof(), "alice",
                "secret".toCharArray(), NOW.plusSeconds(2))).isInstanceOf(IdpProtocolService.ProtocolException.class);
        verifyNoMoreInteractions(codes);
    }

    @Test void expiredInteractionDoesNotVerifyCredentials() {
        configure(1);
        var start = interactions.begin(protocol.validate(request()), NOW);
        assertThatThrownBy(() -> interactions.complete(start.formProof(), start.browserProof(), "alice",
                "secret".toCharArray(), NOW.plusSeconds(300))).isInstanceOf(IdpProtocolService.ProtocolException.class);
        verifyNoInteractions(login, codes);
    }

    @Test void promptNoneNeverStartsInteractionAndPromptLoginDoesNotReuseSession() {
        configure(1);
        var controller = new IdpAuthorizationController(protocol, interactions, sessions, new IdpCsrfTokenCodec());
        when(sessions.resolve(eq(tenant), isNull(), any(Instant.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.authorize("code", "client", REDIRECT, "openid", STATE,
                "a".repeat(43), "S256", null, "none", null, null))
                .isInstanceOf(IdpProtocolService.ProtocolException.class).hasMessage("login_required");
        assertThat(repo.value).isNull();
        var response = controller.authorize("code", "client", REDIRECT, "openid", STATE,
                "a".repeat(43), "S256", null, "login", null, "existing");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Cache-Control")).contains("no-store");
        assertThat(response.getHeaders().getFirst("Set-Cookie")).contains("HttpOnly", "Secure", "SameSite=Strict");
        assertThat(response.getBody().toString()).contains("name=\"interaction\"").doesNotContain(REDIRECT);
        verify(sessions, never()).resolve(eq(tenant), eq("existing"), any(Instant.class));
        verifyNoInteractions(codes);
    }

    private static final class MemoryInteractions implements IdpLoginInteractionRepository {
        private Interaction value;
        @Override public void insert(Interaction interaction) { value = interaction; }
        @Override public Optional<Interaction> consume(String formHash, String browserHash, Instant now) {
            if (value == null || !value.formHash().equals(formHash) || !value.browserHash().equals(browserHash)
                    || !now.isBefore(value.expiresAt())) return Optional.empty();
            var consumed = value; value = null; return Optional.of(consumed);
        }
    }
}
