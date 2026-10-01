package io.wyrmgate.iam.api.administration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorityService;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassService;
import io.wyrmgate.iam.administration.application.AdministrativeElevationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassState;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceLevel;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdministrationControllerTest {
    private static final String ACTOR_ATTRIBUTE =
            ControlPlaneActorRequestContext.class.getName() + ".actor";

    private final AdministrativeAuthorityService authority = mock(AdministrativeAuthorityService.class);
    private final AdministrativeElevationService elevations = mock(AdministrativeElevationService.class);
    private final AdministrativeBreakGlassService breakGlass = mock(AdministrativeBreakGlassService.class);
    private final AdministrationApiMutationService mutations = mock(AdministrationApiMutationService.class);
    private final AdministrationCursorCodec cursors = mock(AdministrationCursorCodec.class);
    private final IdGenerator ids = mock(IdGenerator.class);

    private final UUID correlationId = UUID.randomUUID();
    private final AuthenticatedAdministrativeActor actor =
            new AuthenticatedAdministrativeActor(
                    new TenantContext(UUID.randomUUID()), UUID.randomUUID());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(ids.nextId()).thenReturn(correlationId);
        mvc = MockMvcBuilders.standaloneSetup(
                        new AdministrationController(
                                authority, elevations, breakGlass, mutations, cursors, ids))
                .setControllerAdvice(new AdministrationApiErrorHandler(ids))
                .build();
    }

    @Test
    void roleCreateUsesSemanticPayloadAndReturnsRevisionHeaders() throws Exception {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        UUID roleId = UUID.randomUUID();
        AdministrativeRole role = new AdministrativeRole(
                roleId, "identity-reader", "Identity Reader",
                Set.of(new AdministrativePermission("identity", "read")),
                1, now, now);
        when(mutations.createRole(
                        eq(actor), eq("identity-reader"), eq("Identity Reader"), any(),
                        eq("idem-role-0001"), any(), any(), eq(correlationId)))
                .thenReturn(role);

        mvc.perform(post("/api/v1/administrative-roles")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "idem-role-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"identity-reader",
                                  "name":"Identity Reader",
                                  "permissions":["identity:read"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"rev-1\""))
                .andExpect(header().string("Location", "/api/v1/administrative-roles/" + roleId))
                .andExpect(jsonPath("$.id").value(roleId.toString()))
                .andExpect(jsonPath("$.permissions[0].key").value("identity:read"));
    }

    @Test
    void malformedRevisionFailsBeforeMutation() throws Exception {
        mvc.perform(patch("/api/v1/administrative-roles/{roleId}", UUID.randomUUID())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "rev-1")
                        .header("Idempotency-Key", "idem-role-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));

        verifyNoInteractions(mutations);
    }

    @Test
    void breakGlassReadRedactsReasonAndAssuranceEvidence() throws Exception {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        UUID operationId = UUID.randomUUID();
        AdministrativeBreakGlassOperation operation = new AdministrativeBreakGlassOperation(
                operationId,
                actor.identityId(),
                UUID.randomUUID(),
                AdministrativeScope.global(),
                "sensitive operational reason",
                "INC-2026-0042",
                now.minusSeconds(30),
                now.plusSeconds(300),
                AuthenticationAssuranceLevel.STRONG,
                now.minusSeconds(60),
                now.minusSeconds(60),
                300,
                AdministrativeBreakGlassState.ACTIVE,
                now.minusSeconds(30),
                null,
                null,
                correlationId,
                null,
                1,
                now.minusSeconds(30),
                now.minusSeconds(30));
        when(breakGlass.get(eq(actor), eq(operationId), any())).thenReturn(operation);

        mvc.perform(get("/api/v1/administrative-break-glass-operations/{operationId}", operationId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"rev-1\""))
                .andExpect(jsonPath("$.incidentReference").value("INC-2026-0042"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.activationAssuranceLevel").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive operational reason"))));
    }
}
