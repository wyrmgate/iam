package io.wyrmgate.iam.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.idp.protocol.IdpAuthorizationController;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionController;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService.ResolvedSession;
import io.wyrmgate.iam.idp.session.IdpBrowserSessionService.SessionContext;
import io.wyrmgate.iam.idp.session.IdpCsrfTokenCodec;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class IdpBrowserSessionAuthenticationFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void derivesTrustedActorFromServerResolvedSession() throws Exception {
        IdpBrowserSessionService sessions = mock(IdpBrowserSessionService.class);
        IdpCsrfTokenCodec csrf = new IdpCsrfTokenCodec();
        TenantContext tenant = new TenantContext(UUID.randomUUID());
        UUID identityId = UUID.randomUUID();
        SessionContext context = new SessionContext(
                UUID.randomUUID(), UUID.randomUUID(), identityId,
                Instant.now(), Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
        when(sessions.resolve(org.mockito.ArgumentMatchers.eq("opaque"), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(Optional.of(new ResolvedSession(tenant, context)));
        IdpBrowserSessionAuthenticationFilter filter = new IdpBrowserSessionAuthenticationFilter(
                sessions, csrf, UUID::randomUUID);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/identities");
        request.setCookies(new Cookie(IdpAuthorizationController.BROWSER_SESSION_COOKIE, "opaque"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(continued).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
        assertThat(ControlPlaneActorRequestContext.require(request).tenant()).isEqualTo(tenant);
        assertThat(ControlPlaneActorRequestContext.require(request).identityId()).isEqualTo(identityId);
    }

    @Test
    void rejectsCookieAuthenticatedMutationWithoutCsrfProof() throws Exception {
        IdpBrowserSessionAuthenticationFilter filter = new IdpBrowserSessionAuthenticationFilter(
                mock(IdpBrowserSessionService.class), new IdpCsrfTokenCodec(), UUID::randomUUID);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/identities");
        request.setCookies(new Cookie(IdpAuthorizationController.BROWSER_SESSION_COOKIE, "opaque"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("must not continue"); });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("csrf_failed");
    }

    @Test
    void acceptsMatchingDoubleSubmitCsrfProof() throws Exception {
        IdpBrowserSessionService sessions = mock(IdpBrowserSessionService.class);
        IdpCsrfTokenCodec csrf = new IdpCsrfTokenCodec();
        String token = csrf.issue();
        TenantContext tenant = new TenantContext(UUID.randomUUID());
        SessionContext context = new SessionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.now(), Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
        when(sessions.resolve(org.mockito.ArgumentMatchers.eq("opaque"), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(Optional.of(new ResolvedSession(tenant, context)));
        IdpBrowserSessionAuthenticationFilter filter = new IdpBrowserSessionAuthenticationFilter(
                sessions, csrf, UUID::randomUUID);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/identities");
        request.setCookies(
                new Cookie(IdpAuthorizationController.BROWSER_SESSION_COOKIE, "opaque"),
                new Cookie(IdpBrowserSessionController.CSRF_COOKIE, token));
        request.addHeader(IdpBrowserSessionController.CSRF_HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(continued).isTrue();
    }
}
