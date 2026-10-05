package io.wyrmgate.iam.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wyrmgate.iam.authentication.domain.AuthenticationAssurance;
import io.wyrmgate.iam.authentication.domain.AuthenticationClient;
import io.wyrmgate.iam.authentication.domain.AuthenticationLoginBinding;
import io.wyrmgate.iam.authentication.domain.AuthenticationSession;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticationDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void clientRequiresOpenIdAndRejectsNonLoopbackHttpRedirects() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new AuthenticationClient(
                id, "wg_client", "Console", AuthenticationClient.ClientType.PUBLIC,
                List.of(URI.create("https://console.example/callback")), List.of(), Set.of("profile"),
                AuthenticationClient.LifecycleState.ACTIVE, 1, NOW, NOW));

        assertThrows(IllegalArgumentException.class, () -> new AuthenticationClient(
                id, "wg_client", "Console", AuthenticationClient.ClientType.PUBLIC,
                List.of(URI.create("http://console.example/callback")), List.of(), Set.of("openid"),
                AuthenticationClient.LifecycleState.ACTIVE, 1, NOW, NOW));

        AuthenticationClient nativeClient = new AuthenticationClient(
                id, "wg_client", "Native", AuthenticationClient.ClientType.PUBLIC,
                List.of(URI.create("http://127.0.0.1:8080/callback")), List.of(), Set.of("openid"),
                AuthenticationClient.LifecycleState.ACTIVE, 1, NOW, NOW);
        assertEquals("wg_client", nativeClient.clientId());
    }

    @Test
    void loginBindingNormalizationIsTenantLocalInputCanonicalization() {
        assertEquals("alice@example.com", AuthenticationLoginBinding.normalize("  Alice@Example.COM  "));
        assertThrows(IllegalArgumentException.class, () -> AuthenticationLoginBinding.normalize("   "));
    }

    @Test
    void activeSessionIsEffectiveOnlyBeforeExpiryAndNeverCarriesBearerSecret() {
        AuthenticationSession session = new AuthenticationSession(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AuthenticationAssurance.BASELINE,
                AuthenticationSession.LifecycleState.ACTIVE, NOW, NOW, NOW.plusSeconds(60), null,
                1, NOW, NOW);
        assertTrue(session.effectiveAt(NOW.plusSeconds(30)));
        assertFalse(session.effectiveAt(NOW.plusSeconds(60)));

        assertThrows(IllegalArgumentException.class, () -> new AuthenticationSession(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AuthenticationAssurance.BASELINE,
                AuthenticationSession.LifecycleState.REVOKED, NOW, NOW, NOW.plusSeconds(60), null,
                1, NOW, NOW));
    }
}