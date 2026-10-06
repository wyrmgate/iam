package io.wyrmgate.iam.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SsoClientRegistrationTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Test
    void acceptsExactHttpsAndLoopbackRedirectsWithOpenid() {
        SsoClientRegistration registration = registration(
                Set.of("https://app.example.test/callback", "http://127.0.0.1:49152/callback"),
                Set.of(SsoClientScope.OPENID, SsoClientScope.PROFILE));

        assertThat(registration.redirectUris()).containsExactlyInAnyOrder(
                "https://app.example.test/callback", "http://127.0.0.1:49152/callback");
        assertThat(registration.allowedScopes()).contains(SsoClientScope.OPENID);
        assertThat(registration.requiresGovernedAccess()).isTrue();
    }

    @Test
    void rejectsNonLoopbackHttpRedirect() {
        assertThatThrownBy(() -> registration(
                Set.of("http://app.example.test/callback"), Set.of(SsoClientScope.OPENID)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsRedirectFragmentsAndWildcardHosts() {
        assertThatThrownBy(() -> registration(
                Set.of("https://app.example.test/callback#fragment"), Set.of(SsoClientScope.OPENID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registration(
                Set.of("https://*.example.test/callback"), Set.of(SsoClientScope.OPENID)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresOpenidAndRejectsEmptyRedirects() {
        assertThatThrownBy(() -> registration(
                Set.of("https://app.example.test/callback"), Set.of(SsoClientScope.PROFILE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openid");
        assertThatThrownBy(() -> registration(Set.of(), Set.of(SsoClientScope.OPENID)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void protocolScopesRemainBoundedAndAreNotAdministrationPermissionStrings() {
        assertThat(SsoClientScope.values())
                .extracting(SsoClientScope::protocolValue)
                .containsExactlyInAnyOrder("openid", "profile", "email")
                .noneMatch(value -> value.contains(":"));
    }

    private static SsoClientRegistration registration(
            Set<String> redirectUris, Set<SsoClientScope> scopes) {
        return new SsoClientRegistration(
                UUID.fromString("0199b2de-7d01-7000-8000-000000000001"),
                UUID.fromString("0199b2de-7d01-7000-8000-000000000002"),
                "wc_0199b2de7d0170008000000000000003",
                redirectUris,
                scopes,
                true,
                SsoClientLifecycleState.ACTIVE,
                1,
                NOW,
                NOW);
    }
}
