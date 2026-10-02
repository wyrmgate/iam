package io.wyrmgate.iam.api.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.audit.application.AuditExportService;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditFilter;
import io.wyrmgate.iam.audit.domain.AuditExportOperation;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuditExportControllerTest {

    private static final String ACTOR_ATTRIBUTE =
            ControlPlaneActorRequestContext.class.getName() + ".actor";
    private static final Instant NOW = Instant.parse("2026-10-02T04:00:00Z");

    private final AuditExportService exports = mock(AuditExportService.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final TenantContext tenant = new TenantContext(UUID.randomUUID());
    private final AuthenticatedAdministrativeActor actor =
            new AuthenticatedAdministrativeActor(tenant, UUID.randomUUID());
    private final UUID correlationId = UUID.randomUUID();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(ids.nextId()).thenReturn(correlationId);
        when(exports.enabled()).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(
                        new AuditExportController(exports, authorization(), ids))
                .setControllerAdvice(new AuditApiErrorHandler(ids))
                .build();
    }

    @Test
    void createRequiresIdempotencyAndReturnsAsyncOperationWithoutArtifactReference() throws Exception {
        UUID exportId = UUID.randomUUID();
        AuditExportOperation operation = requested(exportId);
        when(exports.request(
                        eq(tenant),
                        eq(actor.identityId()),
                        any(AuditFilter.class),
                        eq(NOW.minusSeconds(3600)),
                        eq(NOW),
                        eq("audit-export-test"),
                        any()))
                .thenReturn(operation);

        mvc.perform(post("/api/v1/audit-exports")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "audit-export-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actionType":"identity:create",
                                  "occurredFrom":"2026-10-02T03:00:00Z",
                                  "occurredUntil":"2026-10-02T04:00:00Z"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/audit-exports/" + exportId))
                .andExpect(jsonPath("$.id").value(exportId.toString()))
                .andExpect(jsonPath("$.state").value("REQUESTED"))
                .andExpect(jsonPath("$.artifactReference").doesNotExist());
    }

    @Test
    void getNeverExposesInternalArtifactReference() throws Exception {
        UUID exportId = UUID.randomUUID();
        AuditExportOperation completed = new AuditExportOperation(
                exportId,
                actor.identityId(),
                AuditFilter.none(),
                NOW.minusSeconds(3600),
                NOW,
                NOW,
                AuditExportOperation.NDJSON_V1,
                AuditExportOperation.State.SUCCEEDED,
                NOW.minusSeconds(1),
                UUID.randomUUID(),
                2,
                300,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                tenant.tenantId() + "/" + exportId + ".ndjson",
                NOW.plusSeconds(3600),
                null,
                4,
                NOW,
                NOW.minusSeconds(10),
                NOW);
        when(exports.find(tenant, exportId)).thenReturn(completed);

        mvc.perform(get("/api/v1/audit-exports/{id}", exportId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"rev-4\""))
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.sha256").exists())
                .andExpect(jsonPath("$.artifactReference").doesNotExist());
    }

    private AuditExportOperation requested(UUID exportId) {
        return new AuditExportOperation(
                exportId,
                actor.identityId(),
                new AuditFilter(null, "identity:create", null, null, null, null),
                NOW.minusSeconds(3600),
                NOW,
                NOW,
                AuditExportOperation.NDJSON_V1,
                AuditExportOperation.State.REQUESTED,
                null,
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                1,
                null,
                NOW,
                NOW);
    }

    private AdministrativeAuthorizationService authorization() {
        AdministrativeGrant grant = new AdministrativeGrant(
                UUID.randomUUID(),
                actor.identityId(),
                UUID.randomUUID(),
                AdministrativeScope.global(),
                AdministrativeGrantState.ACTIVE,
                NOW.minusSeconds(60),
                null,
                1,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60));
        return new AdministrativeAuthorizationService(
                (requestedTenant, identityId, permission) ->
                        requestedTenant.equals(tenant)
                                        && identityId.equals(actor.identityId())
                                        && permission.equals(AdministrativePermissions.AUDIT_EXPORT)
                                ? List.of(grant)
                                : List.of(),
                (requestedTenant, identityId) ->
                        requestedTenant.equals(tenant)
                                && identityId.equals(actor.identityId()));
    }
}
