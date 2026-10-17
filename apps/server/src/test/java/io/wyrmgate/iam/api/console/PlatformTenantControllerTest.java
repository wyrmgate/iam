package io.wyrmgate.iam.api.console;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.application.PlatformOwnerAuthorityQuery;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.platform.tenant.PlatformTenantOverviewQuery;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlatformTenantControllerTest {
    private static final String ACTOR_ATTRIBUTE =
            ControlPlaneActorRequestContext.class.getName() + ".actor";

    @Test
    void viablePlatformOwnerCanReadCanonicalTenantPage() throws Exception {
        var platformOwners = mock(PlatformOwnerAuthorityQuery.class);
        var tenants = mock(PlatformTenantOverviewQuery.class);
        var cursors = mock(PlatformTenantCursorCodec.class);
        var actor = new AuthenticatedAdministrativeActor(
                new TenantContext(UUID.randomUUID()), UUID.randomUUID());
        Instant created = Instant.parse("2026-10-17T00:00:00Z");
        var first = new PlatformTenantOverviewQuery.TenantSummary(
                UUID.randomUUID(), "Alpha", 1, created, created.plusSeconds(30));
        var second = new PlatformTenantOverviewQuery.TenantSummary(
                UUID.randomUUID(), "Beta", 2, created.plusSeconds(1), created.plusSeconds(60));
        when(platformOwners.isViablePlatformOwner(eq(actor.tenant()), eq(actor.identityId()), any())).thenReturn(true);
        when(tenants.listAfter(null, null, 2)).thenReturn(List.of(first, second));
        when(cursors.encode(any())).thenReturn("next-signed-cursor");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new PlatformTenantController(platformOwners, tenants, cursors))
                .build();

        mvc.perform(get("/api/v1/console/platform/tenants")
                        .param("limit", "1")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.id().toString()))
                .andExpect(jsonPath("$.items[0].displayName").value("Alpha"))
                .andExpect(jsonPath("$.items[0].revision").value(1))
                .andExpect(jsonPath("$.nextCursor").value("next-signed-cursor"));
    }

    @Test
    void tenantScopedAdministratorCannotReadDeploymentTenantInventory() throws Exception {
        var platformOwners = mock(PlatformOwnerAuthorityQuery.class);
        var tenants = mock(PlatformTenantOverviewQuery.class);
        var cursors = mock(PlatformTenantCursorCodec.class);
        var actor = new AuthenticatedAdministrativeActor(
                new TenantContext(UUID.randomUUID()), UUID.randomUUID());
        when(platformOwners.isViablePlatformOwner(eq(actor.tenant()), eq(actor.identityId()), any())).thenReturn(false);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new PlatformTenantController(platformOwners, tenants, cursors))
                .build();

        mvc.perform(get("/api/v1/console/platform/tenants")
                        .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isForbidden());
        verifyNoInteractions(tenants, cursors);
    }
}
