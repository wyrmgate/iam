package io.wyrmgate.iam.idp.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery;
import io.wyrmgate.iam.catalog.application.SsoClientProtocolQuery.ResolvedClient;
import io.wyrmgate.iam.catalog.domain.SsoClientLifecycleState;
import io.wyrmgate.iam.catalog.domain.SsoClientRegistration;
import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.idp.protocol.IdpProtocolService.AuthorizationRequest;
import io.wyrmgate.iam.idp.protocol.IdpProtocolService.ProtocolException;
import io.wyrmgate.iam.idp.protocol.IdpProtocolService.TokenRequest;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService.SessionContext;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdpProtocolServiceTest {

    private static final TenantContext TENANT = new TenantContext(UUID.randomUUID());
    private static final UUID REGISTRATION_ID = UUID.randomUUID();
    private static final UUID APPLICATION_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID PRINCIPAL_ID = UUID.randomUUID();
    private static final UUID IDENTITY_ID = UUID.randomUUID();
    private static final String CLIENT_ID = "wc_1234567890abcdef1234567890abcdef";
    private static final String REDIRECT_URI = "https://app.example.test/callback";
    private static final String STATE = "state-1234567890abcdef";
    private static final String NONCE = "nonce-1234567890abcdef";
    private static final String SESSION_TOKEN = "browser-session-token";
    private static final String VERIFIER =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    private static final Instant NOW = Instant.parse("2026-10-10T02:00:00Z");

    @Test
    void authorizationCodeRoundTripUsesServerDerivedTenantExactRedirectAndS256() {
        SsoClientRegistration client = client(false);
        SsoClientProtocolQuery clients = clientQuery(client);
        IdpBrowserSessionService sessions = mock(IdpBrowserSessionService.class);
        when(sessions.resolve(TENANT, SESSION_TOKEN, NOW))
                .thenReturn(Optional.of(sessionContext()));
        InMemoryCodes codes = new InMemoryCodes();
        IdpProtocolService service = service(
                clients, sessions, (tenant, identity, application, at) -> true, codes);

        var authorized = service.authorize(
                authorizeRequest(REDIRECT_URI, challenge(VERIFIER)),
                SESSION_TOKEN,
                NOW);

        assertThat(authorized.redirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(authorized.state()).isEqualTo(STATE);
        assertThat(authorized.code()).hasSize(43);
        assertThat(codes.values())
                .singleElement()
                .satisfies(stored -> {
                    assertThat(stored.codeHash()).doesNotContain(authorized.code());
                    assertThat(stored.redirectUri()).isEqualTo(REDIRECT_URI);
                    assertThat(stored.clientRegistrationId()).isEqualTo(REGISTRATION_ID);
                });
        verify(sessions).resolve(TENANT, SESSION_TOKEN, NOW);

        var token = service.redeem(
                new TokenRequest(
                        "authorization_code",
                        authorized.code(),
                        CLIENT_ID,
                        REDIRECT_URI,
                        VERIFIER),
                NOW.plusSeconds(1));

        assertThat(token.accessToken()).isEqualTo("access.jwt");
        assertThat(token.idToken()).isEqualTo("id.jwt");
        assertThat(token.scope()).isEqualTo("openid profile");

        assertThatThrownBy(() -> service.redeem(
                        new TokenRequest(
                                "authorization_code",
                                authorized.code(),
                                CLIENT_ID,
                                REDIRECT_URI,
                                VERIFIER),
                        NOW.plusSeconds(2)))
                .isInstanceOf(ProtocolException.class)
                .extracting(error -> ((ProtocolException) error).error())
                .isEqualTo("invalid_grant");
    }

    @Test
    void unregisteredRedirectIsRejectedBeforeBrowserSessionResolution() {
        IdpBrowserSessionService sessions = mock(IdpBrowserSessionService.class);
        InMemoryCodes codes = new InMemoryCodes();
        IdpProtocolService service = service(
                clientQuery(client(false)),
                sessions,
                (tenant, identity, application, at) -> true,
                codes);

        assertThatThrownBy(() -> service.authorize(
                        authorizeRequest(
                                "https://attacker.example.test/callback",
                                challenge(VERIFIER)),
                        SESSION_TOKEN,
                        NOW))
                .isInstanceOf(ProtocolException.class)
                .extracting(error -> ((ProtocolException) error).error())
                .isEqualTo("invalid_request");

        verifyNoInteractions(sessions);
        assertThat(codes.values()).isEmpty();
    }

    @Test
    void mandatoryGovernedAccessFailureFailsClosedWithoutIssuingCode() {
        IdpBrowserSessionService sessions = mock(IdpBrowserSessionService.class);
        when(sessions.resolve(TENANT, SESSION_TOKEN, NOW))
                .thenReturn(Optional.of(sessionContext()));
        InMemoryCodes codes = new InMemoryCodes();
        IdpProtocolService service = service(
                clientQuery(client(true)),
                sessions,
                (tenant, identity, application, at) -> {
                    throw new IllegalStateException("governance projection unavailable");
                },
                codes);

        assertThatThrownBy(() -> service.authorize(
                        authorizeRequest(REDIRECT_URI, challenge(VERIFIER)),
                        SESSION_TOKEN,
                        NOW))
                .isInstanceOf(ProtocolException.class)
                .satisfies(error -> {
                    ProtocolException protocol = (ProtocolException) error;
                    assertThat(protocol.error()).isEqualTo("temporarily_unavailable");
                    assertThat(protocol.redirectUri()).contains(REDIRECT_URI);
                });
        assertThat(codes.values()).isEmpty();
    }

    @Test
    void failedPkceAttemptBurnsTheSingleUseCode() {
        IdpBrowserSessionService sessions = mock(IdpBrowserSessionService.class);
        when(sessions.resolve(TENANT, SESSION_TOKEN, NOW))
                .thenReturn(Optional.of(sessionContext()));
        InMemoryCodes codes = new InMemoryCodes();
        IdpProtocolService service = service(
                clientQuery(client(false)),
                sessions,
                (tenant, identity, application, at) -> true,
                codes);
        var authorized = service.authorize(
                authorizeRequest(REDIRECT_URI, challenge(VERIFIER)),
                SESSION_TOKEN,
                NOW);

        String wrongVerifier = "A".repeat(43);
        assertThatThrownBy(() -> service.redeem(
                        new TokenRequest(
                                "authorization_code",
                                authorized.code(),
                                CLIENT_ID,
                                REDIRECT_URI,
                                wrongVerifier),
                        NOW.plusSeconds(1)))
                .isInstanceOf(ProtocolException.class)
                .extracting(error -> ((ProtocolException) error).error())
                .isEqualTo("invalid_grant");

        assertThatThrownBy(() -> service.redeem(
                        new TokenRequest(
                                "authorization_code",
                                authorized.code(),
                                CLIENT_ID,
                                REDIRECT_URI,
                                VERIFIER),
                        NOW.plusSeconds(2)))
                .isInstanceOf(ProtocolException.class)
                .extracting(error -> ((ProtocolException) error).error())
                .isEqualTo("invalid_grant");
    }

    private static IdpProtocolService service(
            SsoClientProtocolQuery clients,
            IdpBrowserSessionService sessions,
            IdpApplicationAccessQuery access,
            InMemoryCodes codes) {
        IdpTokenIssuer tokenIssuer = (tenant, code, now) ->
                new IdpTokenIssuer.TokenPair("id.jwt", "access.jwt", 300);
        IdGenerator ids = UUID::randomUUID;
        return new IdpProtocolService(
                clients, sessions, access, codes, tokenIssuer, ids);
    }

    private static SsoClientProtocolQuery clientQuery(SsoClientRegistration client) {
        return clientId -> CLIENT_ID.equals(clientId)
                ? Optional.of(new ResolvedClient(TENANT, client))
                : Optional.empty();
    }

    private static SsoClientRegistration client(boolean requiresGovernedAccess) {
        return new SsoClientRegistration(
                REGISTRATION_ID,
                APPLICATION_ID,
                CLIENT_ID,
                Set.of(REDIRECT_URI),
                Set.of(SsoClientScope.OPENID, SsoClientScope.PROFILE),
                requiresGovernedAccess,
                SsoClientLifecycleState.ACTIVE,
                7,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(60));
    }

    private static SessionContext sessionContext() {
        return new SessionContext(
                SESSION_ID,
                PRINCIPAL_ID,
                IDENTITY_ID,
                NOW.minusSeconds(120),
                NOW.plusSeconds(1200),
                NOW.plusSeconds(7200));
    }

    private static AuthorizationRequest authorizeRequest(
            String redirectUri,
            String challenge) {
        return new AuthorizationRequest(
                "code",
                CLIENT_ID,
                redirectUri,
                "openid profile",
                STATE,
                challenge,
                "S256",
                NONCE);
    }

    private static String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                IdpJwtIssuer.sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static final class InMemoryCodes implements IdpAuthorizationCodeRepository {
        private final Map<String, IdpAuthorizationCode> byHash = new LinkedHashMap<>();

        @Override
        public void insert(TenantContext tenant, IdpAuthorizationCode code) {
            byHash.put(code.codeHash(), code);
        }

        @Override
        public Optional<IdpAuthorizationCode> consume(
                TenantContext tenant,
                String codeHash,
                Instant consumedAt) {
            IdpAuthorizationCode code = byHash.remove(codeHash);
            if (code == null || !consumedAt.isBefore(code.expiresAt())) {
                return Optional.empty();
            }
            return Optional.of(code);
        }

        java.util.Collection<IdpAuthorizationCode> values() {
            return byHash.values();
        }
    }
}
