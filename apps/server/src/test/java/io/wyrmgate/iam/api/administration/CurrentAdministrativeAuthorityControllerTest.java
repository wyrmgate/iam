package io.wyrmgate.iam.api.administration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.application.CurrentAdministrativeAuthorityProjection;
import io.wyrmgate.iam.administration.application.EffectiveAdministrativeAuthority;
import io.wyrmgate.iam.administration.application.PlatformOwnerAuthorityQuery;
import io.wyrmgate.iam.administration.domain.AdministrativeAuthoritySource;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CurrentAdministrativeAuthorityControllerTest {
    private static final String ACTOR_ATTRIBUTE =
            ControlPlaneActorRequestContext.class.getName() + ".actor";

    @Test
    void returnsOnlyTrustedActorContextTypedAuthorityAndPlatformOwnerHint() throws Exception {
        var projection = mock(CurrentAdministrativeAuthorityProjection.class);
        var platformOwners = mock(PlatformOwnerAuthorityQuery.class);
        var ids = mock(IdGenerator.class);
        UUID correlationId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        var actor = new AuthenticatedAdministrativeActor(
                new TenantContext(tenantId), identityId);
        var authority = new EffectiveAdministrativeAuthority(
                new AdministrativePermission("identity", "read"),
                AdministrativeScope.specificResource("identity", resourceId),
                AdministrativeAuthoritySource.DIRECT_GRANT,
                sourceId,
                null,
                null);
        when(ids.nextId()).thenReturn(correlationId);
        when(projection.current(eq(actor), any()))
                .thenReturn(new CurrentAdministrativeAuthorityProjection.Result(true, List.of(authority)));
        when(platformOwners.isViablePlatformOwner(eq(actor.tenant()), eq(identityId), any())).thenReturn(true);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new CurrentAdministrativeAuthorityController(projection, platformOwners, ids))
                .setControllerAdvice(new AdministrationApiErrorHandler(ids))
                .build();

        mvc.perform(get("/api/v1/current-administrative-authority")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("X-Tenant-Id", UUID.randomUUID().toString())
                        .header("X-Administrative-Permissions", "administration:manage-authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Correlation-Id", correlationId.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.actorIdentityId").value(identityId.toString()))
                .andExpect(jsonPath("$.administrativelyEligible").value(true))
                .andExpect(jsonPath("$.viablePlatformOwner").value(true))
                .andExpect(jsonPath("$.authorities[0].permission.key").value("identity:read"))
                .andExpect(jsonPath("$.authorities[0].scope.type").value("SPECIFIC_RESOURCE"))
                .andExpect(jsonPath("$.authorities[0].scope.resourceId").value(resourceId.toString()))
                .andExpect(jsonPath("$.authorities[0].source").value("DIRECT_GRANT"))
                .andExpect(jsonPath("$.authorities[0].sourceId").value(sourceId.toString()));
    }

    @Test
    void suspendedOrOtherwiseIneligibleActorGetsEmptyProjectionAndNoPlatformOwnerHint() throws Exception {
        var projection = mock(CurrentAdministrativeAuthorityProjection.class);
        var platformOwners = mock(PlatformOwnerAuthorityQuery.class);
        var ids = mock(IdGenerator.class);
        UUID correlationId = UUID.randomUUID();
        var actor = new AuthenticatedAdministrativeActor(
                new TenantContext(UUID.randomUUID()), UUID.randomUUID());
        when(ids.nextId()).thenReturn(correlationId);
        when(projection.current(eq(actor), any()))
                .thenReturn(new CurrentAdministrativeAuthorityProjection.Result(false, List.of()));
        when(platformOwners.isViablePlatformOwner(eq(actor.tenant()), eq(actor.identityId()), any())).thenReturn(false);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new CurrentAdministrativeAuthorityController(projection, platformOwners, ids))
                .setControllerAdvice(new AdministrationApiErrorHandler(ids))
                .build();

        mvc.perform(get("/api/v1/current-administrative-authority")
                        .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.administrativelyEligible").value(false))
                .andExpect(jsonPath("$.viablePlatformOwner").value(false))
                .andExpect(jsonPath("$.authorities").isEmpty());
    }
}
