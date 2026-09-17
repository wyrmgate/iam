package io.wyrmgate.iam.api.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationDecision;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.application.ControlPlaneActorResolver;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.api.security.ControlPlaneSecurityConfiguration;
import io.wyrmgate.iam.identity.application.IdentityQueryService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        value = IdentityController.class,
        properties = {
            "iam.auth.enabled=true",
            "iam.auth.issuer-uri=https://issuer.example.test",
            "iam.auth.audience=wyrmgate-api"
        })
@Import(ControlPlaneSecurityConfiguration.class)
class IdentityHttpSecurityIntegrationTest {

    private static final String ISSUER = "https://issuer.example.test";
    private static final String SUBJECT = "operator-123";
    private static final UUID CORRELATION_ID = UUID.fromString("0199d839-8c00-7000-8000-000000000001");
    private static final TenantContext TENANT =
            new TenantContext(UUID.fromString("0199d839-8c00-7000-8000-000000000002"));
    private static final AuthenticatedAdministrativeActor ACTOR = new AuthenticatedAdministrativeActor(
            TENANT, UUID.fromString("0199d839-8c00-7000-8000-000000000003"));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IdentityQueryService queries;

    @MockitoBean
    private IdentityApiMutationService mutations;

    @MockitoBean
    private AdministrativeAuthorizationService authorization;

    @MockitoBean
    private ControlPlaneActorResolver actorResolver;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdGenerator ids;

    @BeforeEach
    void stableCorrelationId() {
        when(ids.nextId()).thenReturn(CORRELATION_ID);
    }

    @Test
    void protectedIdentityRouteRejectsUnauthenticatedCaller() throws Exception {
        mvc.perform(get("/api/v1/identities"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("authentication_required"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID.toString()));

        verifyNoInteractions(actorResolver, authorization, queries, mutations);
    }

    @Test
    void invalidBearerRemainsAuthenticationFailure() throws Exception {
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid token"));

        mvc.perform(get("/api/v1/identities")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("authentication_required"));

        verifyNoInteractions(actorResolver, authorization, queries, mutations);
    }

    @Test
    void authenticatedButUnboundActorFailsClosed() throws Exception {
        when(jwtDecoder.decode("valid-unbound-token")).thenReturn(jwt("valid-unbound-token"));
        when(actorResolver.resolve(new ExternalAuthenticationSubject(ISSUER, SUBJECT)))
                .thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/identities")
                        .header("Authorization", "Bearer valid-unbound-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unresolved_actor"));

        verifyNoInteractions(authorization, queries, mutations);
    }

    @Test
    void bearerRolesAndScopesDoNotGrantIamAdministrativePermission() throws Exception {
        when(jwtDecoder.decode("valid-bound-token")).thenReturn(jwt("valid-bound-token"));
        when(actorResolver.resolve(new ExternalAuthenticationSubject(ISSUER, SUBJECT)))
                .thenReturn(Optional.of(ACTOR));
        when(authorization.authorize(any(), any(), any(), any()))
                .thenReturn(AdministrativeAuthorizationDecision.deny("no_effective_grant"));

        mvc.perform(get("/api/v1/identities")
                        .header("Authorization", "Bearer valid-bound-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    private static Jwt jwt(String tokenValue) {
        Instant now = Instant.parse("2026-09-17T03:30:00Z");
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject(SUBJECT)
                .issuedAt(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(300))
                .claim("roles", List.of("administrator"))
                .claim("scope", "identity:read identity:create identity:update")
                .build();
    }
}
