package io.wyrmgate.iam.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.application.ControlPlaneActorResolver;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ControlPlaneActorResolutionFilterTest {

    private static final String ISSUER = "https://issuer.example.test";
    private static final String SUBJECT = "operator-123";
    private static final UUID CORRELATION_ID = UUID.fromString("0199d839-8c00-7000-8000-000000000001");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedButUnboundSubjectFailsClosed() throws Exception {
        ControlPlaneActorResolver resolver = mock(ControlPlaneActorResolver.class);
        IdGenerator ids = mock(IdGenerator.class);
        when(ids.nextId()).thenReturn(CORRELATION_ID);
        when(resolver.resolve(new ExternalAuthenticationSubject(ISSUER, SUBJECT)))
                .thenReturn(Optional.empty());
        authenticate();

        MockHttpServletRequest request = protectedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneActorResolutionFilter(resolver, ids)
                .doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("unresolved_actor");
        assertThat(response.getContentAsString()).contains(CORRELATION_ID.toString());
        assertThat(ControlPlaneActorRequestContext.ATTRIBUTE).isNotBlank();
        assertThat(request.getAttribute(ControlPlaneActorRequestContext.ATTRIBUTE)).isNull();
    }

    @Test
    void resolvedSubjectPropagatesTrustedActorContextWithoutUsingTokenRolesOrScopes() throws Exception {
        ControlPlaneActorResolver resolver = mock(ControlPlaneActorResolver.class);
        IdGenerator ids = mock(IdGenerator.class);
        TenantContext tenant = new TenantContext(UUID.fromString("0199d839-8c00-7000-8000-000000000002"));
        AuthenticatedAdministrativeActor actor = new AuthenticatedAdministrativeActor(
                tenant, UUID.fromString("0199d839-8c00-7000-8000-000000000003"));
        when(resolver.resolve(new ExternalAuthenticationSubject(ISSUER, SUBJECT)))
                .thenReturn(Optional.of(actor));
        authenticate();

        MockHttpServletRequest request = protectedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ControlPlaneActorResolutionFilter(resolver, ids)
                .doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(ControlPlaneActorRequestContext.require(request)).isEqualTo(actor);
    }

    @Test
    void actorResolutionFailureReturnsStableUnavailableError() throws Exception {
        ControlPlaneActorResolver resolver = mock(ControlPlaneActorResolver.class);
        IdGenerator ids = mock(IdGenerator.class);
        when(ids.nextId()).thenReturn(CORRELATION_ID);
        when(resolver.resolve(new ExternalAuthenticationSubject(ISSUER, SUBJECT)))
                .thenThrow(new IllegalStateException("database unavailable"));
        authenticate();

        MockHttpServletRequest request = protectedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ControlPlaneActorResolutionFilter(resolver, ids)
                .doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("authentication_context_unavailable");
        assertThat(response.getContentAsString()).doesNotContain("database unavailable");
    }

    private static MockHttpServletRequest protectedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/identities");
        request.setRequestURI("/api/v1/identities");
        return request;
    }

    private static void authenticate() {
        Instant now = Instant.parse("2026-09-17T03:30:00Z");
        Jwt jwt = Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject(SUBJECT)
                .issuedAt(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(300))
                .claim("roles", List.of("administrator"))
                .claim("scope", "identity:read identity:create identity:update")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(), SUBJECT));
    }
}
