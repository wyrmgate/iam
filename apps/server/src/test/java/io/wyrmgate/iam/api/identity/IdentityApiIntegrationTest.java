package io.wyrmgate.iam.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.identity.application.CanonicalAttributeResolutionEvaluator;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityMergeSplitService;
import io.wyrmgate.iam.identity.application.IdentityQueryService;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.persistence.JdbcCanonicalAttributeReadRepository;
import io.wyrmgate.iam.identity.persistence.JdbcCanonicalAttributeRepository;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityMergeSplitRepository;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityQueryRepository;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcSourceCorrelationFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcSourceCorrelationRepository;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;

class IdentityApiIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");
    private static final String ACTOR_ATTRIBUTE = ControlPlaneActorRequestContext.class.getName() + ".actor";

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcIdentityRepository identities;
    private static IdentityCommandService commands;
    private static JdbcIdempotencyRepository idempotency;
    private static TransactionExecutor transactions;
    private static JdbcCanonicalAttributeRepository canonicalAttributes;
    private static IdentityQueryService queries;

    private TenantContext tenant;
    private Identity actorIdentity;
    private AuthenticatedAdministrativeActor actor;
    private MockMvc authorized;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        identities = new JdbcIdentityRepository(jdbc);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc);
        commands = new IdentityCommandService(
                identities,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);
        idempotency = new JdbcIdempotencyRepository(jdbc, ids);
        canonicalAttributes = new JdbcCanonicalAttributeRepository(jdbc, ids);
        CanonicalAttributeResolutionEvaluator evaluator = new CanonicalAttributeResolutionEvaluator();
        queries = new IdentityQueryService(
                identities,
                new JdbcIdentityQueryRepository(jdbc),
                canonicalAttributes,
                new JdbcCanonicalAttributeReadRepository(jdbc),
                evaluator);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("41");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetDatabaseAndController() {
        jdbc.execute("""
                TRUNCATE TABLE
                    platform.scheduled_work,
                    platform.idempotency_record,
                    platform.inbox_message,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
        Instant now = Instant.parse("2026-09-16T10:00:00Z");
        tenant = new TenantContext(tenants.create("HTTP Tenant", now).id());
        actorIdentity = commands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                "API Administrator",
                now,
                ids.nextId(),
                null);
        actor = new AuthenticatedAdministrativeActor(tenant, actorIdentity.id());
        authorized = mockMvc(authorization(true));
    }

    @Test
    void createReadListUpdateAndReplayFollowTheContract() throws Exception {
        UUID correlationId = ids.nextId();
        String createBody = """
                {"type":"SERVICE","profile":{"kind":"SERVICE"},"lifecycleState":"PENDING","displayName":"Billing Service"}
                """;

        String location = authorized.perform(post("/api/v1/identities")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("X-Correlation-Id", correlationId)
                        .header("Idempotency-Key", "create-service-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-Id", correlationId.toString()))
                .andExpect(header().string("ETag", "\"rev-1\""))
                .andExpect(jsonPath("$.type").value("SERVICE"))
                .andExpect(jsonPath("$.profile.kind").value("SERVICE"))
                .andExpect(jsonPath("$.displayName").value("Billing Service"))
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).startsWith("/api/v1/identities/");
        UUID createdId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        authorized.perform(post("/api/v1/identities")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "create-service-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(createdId.toString()));

        Integer identityCount = jdbc.queryForObject(
                "SELECT count(*) FROM identity.identity WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId());
        assertThat(identityCount).isEqualTo(2);

        authorized.perform(get("/api/v1/identities/{identityId}", createdId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"rev-1\""))
                .andExpect(jsonPath("$.id").value(createdId.toString()));

        authorized.perform(get("/api/v1/identities")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").isString());

        authorized.perform(patch("/api/v1/identities/{identityId}", createdId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "update-service-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Billing Service v2\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"rev-2\""))
                .andExpect(jsonPath("$.displayName").value("Billing Service v2"))
                .andExpect(jsonPath("$.revision").value(2));

        authorized.perform(patch("/api/v1/identities/{identityId}", createdId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "update-service-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Billing Service v2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void idempotencyConflictStaleRevisionAndValidationAreSemanticErrors() throws Exception {
        String body = """
                {"type":"WORKLOAD","profile":{"kind":"WORKLOAD"},"lifecycleState":"ACTIVE","displayName":"Batch Worker"}
                """;
        String location = authorized.perform(post("/api/v1/identities")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "create-workload-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        UUID createdId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        authorized.perform(post("/api/v1/identities")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "create-workload-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"WORKLOAD","profile":{"kind":"WORKLOAD"},"lifecycleState":"ACTIVE","displayName":"Different Worker"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_conflict"));

        authorized.perform(patch("/api/v1/identities/{identityId}", createdId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "update-workload-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Batch Worker v2\"}"))
                .andExpect(status().isOk());

        authorized.perform(patch("/api/v1/identities/{identityId}", createdId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "update-workload-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Stale Worker\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("stale_revision"));

        authorized.perform(get("/api/v1/identities")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .param("cursor", "not-a-valid-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void mergeApiIsIdempotentAndMergeSplitPermissionsAreDefaultDeny() throws Exception {
        Instant now = Instant.parse("2026-09-16T10:45:00Z");
        Identity survivor = commands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                "Survivor",
                now,
                ids.nextId(),
                null);
        Identity absorbed = commands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                "Absorbed",
                now.plusSeconds(1),
                ids.nextId(),
                null);

        String body = "{\"absorbedIdentityId\":\"" + absorbed.id()
                + "\",\"absorbedRevision\":1,\"reason\":\"duplicate confirmed\"}";

        String operationId = authorized.perform(
                        post("/api/v1/identities/{identityId}:merge", survivor.id())
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-1\"")
                                .header("Idempotency-Key", "merge-identity-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.survivorIdentityId").value(survivor.id().toString()))
                .andExpect(jsonPath("$.absorbedIdentityId").value(absorbed.id().toString()))
                .andExpect(jsonPath("$.movedLinkCount").value(0))
                .andExpect(jsonPath("$.movedPrincipalCount").value(0))
                .andReturn().getResponse().getContentAsString();

        authorized.perform(
                        post("/api/v1/identities/{identityId}:merge", survivor.id())
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-1\"")
                                .header("Idempotency-Key", "merge-identity-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.survivorIdentityId").value(survivor.id().toString()));

        assertThat(identities.findById(tenant, absorbed.id()).orElseThrow().lifecycleState())
                .isEqualTo(IdentityLifecycleState.DECOMMISSIONED);
        assertThat(operationId).contains(survivor.id().toString());

        MockMvc updateOnly = mockMvc(
                authorizationOnly(AdministrativePermissions.IDENTITY_UPDATE));
        Identity other = commands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                "Other",
                now.plusSeconds(2),
                ids.nextId(),
                null);
        updateOnly.perform(
                        post("/api/v1/identities/{identityId}:merge", survivor.id())
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-1\"")
                                .header("Idempotency-Key", "merge-identity-0002")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"absorbedIdentityId\":\"" + other.id()
                                        + "\",\"absorbedRevision\":1,\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());

        updateOnly.perform(
                        post("/api/v1/identities/{identityId}:split", survivor.id())
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-1\"")
                                .header("Idempotency-Key", "split-identity-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"newDisplayName\":\"Split\","
                                        + "\"sourceRecordIds\":[\"" + ids.nextId() + "\"],"
                                        + "\"principalIds\":[],\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());

        authorized.perform(
                        post("/api/v1/identities/{identityId}:split", survivor.id())
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-1\"")
                                .header("Idempotency-Key", "split-identity-0002")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"newDisplayName\":\"Split\","
                                        + "\"sourceRecordIds\":[],\"principalIds\":[],"
                                        + "\"reason\":\"test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void lifecycleOperationsPreserveTransitionSemanticsAndEmitFacts() throws Exception {
        Instant now = Instant.parse("2026-09-16T11:00:00Z");
        Identity target = commands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.PENDING,
                "Lifecycle Target",
                now,
                ids.nextId(),
                null);

        authorized.perform(post("/api/v1/identities/{identityId}:activate", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "idempotency-00000001"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"rev-2\""))
                .andExpect(jsonPath("$.lifecycleState").value("ACTIVE"))
                .andExpect(jsonPath("$.revision").value(2));

        authorized.perform(post("/api/v1/identities/{identityId}:activate", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-2\"")
                        .header("Idempotency-Key", "idempotency-00000002"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"rev-2\""))
                .andExpect(jsonPath("$.revision").value(2));

        authorized.perform(post("/api/v1/identities/{identityId}:suspend", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-2\"")
                        .header("Idempotency-Key", "idempotency-00000003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("SUSPENDED"))
                .andExpect(jsonPath("$.revision").value(3));

        authorized.perform(post("/api/v1/identities/{identityId}:deactivate", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-3\"")
                        .header("Idempotency-Key", "deidempotency-00000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("INACTIVE"))
                .andExpect(jsonPath("$.revision").value(4));

        authorized.perform(post("/api/v1/identities/{identityId}:activate", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-4\"")
                        .header("Idempotency-Key", "reidempotency-00000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("ACTIVE"))
                .andExpect(jsonPath("$.revision").value(5));

        UUID correlationId = ids.nextId();
        authorized.perform(post("/api/v1/identities/{identityId}:decommission", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("X-Correlation-Id", correlationId)
                        .header("If-Match", "\"rev-5\"")
                        .header("Idempotency-Key", "idempotency-00000006"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("DECOMMISSIONED"))
                .andExpect(jsonPath("$.revision").value(6));

        authorized.perform(post("/api/v1/identities/{identityId}:activate", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-6\"")
                        .header("Idempotency-Key", "idempotency-00000007"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_lifecycle_transition"));

        Integer lifecycleFactCount = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND aggregate_id = ?
                  AND event_type = 'identity.identity-lifecycle-changed'
                """,
                Integer.class,
                tenant.tenantId(),
                target.id());
        assertThat(lifecycleFactCount).isEqualTo(5);

        Integer eligibilityFactCount = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND aggregate_id = ?
                  AND event_type = 'identity.access-eligibility-changed'
                """,
                Integer.class,
                tenant.tenantId(),
                target.id());
        assertThat(eligibilityFactCount).isEqualTo(4);

        String payload = jdbc.queryForObject(
                """
                SELECT payload::text
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND aggregate_id = ?
                  AND event_type = 'identity.identity-lifecycle-changed'
                  AND aggregate_revision = 6
                """,
                String.class,
                tenant.tenantId(),
                target.id());
        assertThat(payload)
                .contains("\"previousLifecycleState\": \"ACTIVE\"")
                .contains("\"lifecycleState\": \"DECOMMISSIONED\"")
                .contains("\"accessEligible\": false");
    }

    @Test
    void lifecycleOperationsRequireRevisionIdempotencyAndSpecificPermission() throws Exception {
        Instant now = Instant.parse("2026-09-16T11:30:00Z");
        Identity target = commands.create(
                tenant,
                IdentityType.SERVICE,
                new IdentityProfile.ServiceProfile(),
                IdentityLifecycleState.ACTIVE,
                "Permission Target",
                now,
                ids.nextId(),
                null);

        MockMvc updateOnly = mockMvc(authorizationOnly(AdministrativePermissions.IDENTITY_UPDATE));
        updateOnly.perform(post("/api/v1/identities/{identityId}:suspend", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "idempotency-00000008"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        MockMvc suspendOnly = mockMvc(authorizationOnly(AdministrativePermissions.IDENTITY_SUSPEND));
        suspendOnly.perform(post("/api/v1/identities/{identityId}:suspend", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "idempotency-00000009"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));

        suspendOnly.perform(post("/api/v1/identities/{identityId}:suspend", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "idempotency-00000010"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("stale_revision"));

        suspendOnly.perform(post("/api/v1/identities/{identityId}:suspend", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-2\"")
                        .header("Idempotency-Key", "idempotency-00000009"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_conflict"));

        suspendOnly.perform(post("/api/v1/identities/{identityId}:suspend", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "idempotency-00000011"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));

        suspendOnly.perform(post("/api/v1/identities/{identityId}:suspend", target.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-2\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void lifecycleOperationsRespectTenantIsolationAndRejectGenericPatch() throws Exception {
        Instant now = Instant.parse("2026-09-16T12:00:00Z");
        TenantContext otherTenant = new TenantContext(
                tenants.create("Other HTTP Tenant", now).id());
        Identity foreign = commands.create(
                otherTenant,
                IdentityType.WORKLOAD,
                new IdentityProfile.WorkloadProfile(),
                IdentityLifecycleState.ACTIVE,
                "Foreign Target",
                now,
                ids.nextId(),
                null);

        authorized.perform(post("/api/v1/identities/{identityId}:suspend", foreign.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "idempotency-00000012"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));

        authorized.perform(patch("/api/v1/identities/{identityId}", actorIdentity.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "idempotency-00000013")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lifecycleState\":\"SUSPENDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void canonicalReadIsAuthorizedAndFailsClosedOnValueVisibility() throws Exception {
        authorized.perform(get("/api/v1/identities/{identityId}/canonical-attributes", actorIdentity.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void missingAdministrativeGrantReturnsForbiddenBeforeDomainAccess() throws Exception {
        MockMvc denied = mockMvc(authorization(false));
        denied.perform(get("/api/v1/identities/{identityId}", actorIdentity.id())
                        .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    private MockMvc mockMvc(AdministrativeAuthorizationService authorization) {
        IdentityApiMutationService mutations = new IdentityApiMutationService(
                authorization, commands, identities, idempotency, transactions);
        var outbox = new JdbcOutboxRepository(jdbc);
        var sourceRepository = new JdbcSourceCorrelationRepository(jdbc, ids);
        var sourceFacts = new JdbcSourceCorrelationFactSink(outbox, ids);
        var principalFacts = new JdbcPrincipalFactSink(outbox, ids);
        var mergeSplitRepository = new JdbcIdentityMergeSplitRepository(jdbc, identities);
        var mergeSplitService = new IdentityMergeSplitService(
                mergeSplitRepository,
                identities,
                sourceRepository,
                sourceFacts,
                principalFacts,
                commands,
                ids,
                transactions);
        var mergeSplitMutations = new IdentityMergeSplitApiMutationService(
                authorization,
                mergeSplitService,
                mergeSplitRepository,
                idempotency,
                transactions);
        IdentityController controller = new IdentityController(
                queries,
                mutations,
                mergeSplitMutations,
                authorization,
                ids,
                testCursorCodec());
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new IdentityApiErrorHandler(ids))
                .build();
    }

    private IdentityCursorCodec testCursorCodec() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial("api-test", "SHA256withRSA", keyPair.getPublic());
            SigningKeyProvider provider = new SigningKeyProvider() {
                @Override
                public SigningKeyMaterial currentSigningKey() {
                    return material;
                }

                @Override
                public Optional<SigningKeyMaterial> verificationKey(String keyId) {
                    return material.keyId().equals(keyId) ? Optional.of(material) : Optional.empty();
                }

                @Override
                public byte[] sign(byte[] payload) {
                    try {
                        Signature signature = Signature.getInstance(material.signingAlgorithm());
                        signature.initSign(keyPair.getPrivate());
                        signature.update(payload);
                        return signature.sign();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            };
            return new IdentityCursorCodec(provider, Duration.ofMinutes(15), Clock.systemUTC());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AdministrativeAuthorizationService authorizationOnly(
            AdministrativePermission allowedPermission) {
        Instant grantTime = Instant.parse("2026-01-01T00:00:00Z");
        AdministrativeGrant grant = new AdministrativeGrant(
                ids.nextId(),
                actorIdentity.id(),
                ids.nextId(),
                new AdministrativeScope(
                        AdministrativeScopeType.GLOBAL,
                        null,
                        null),
                AdministrativeGrantState.ACTIVE,
                grantTime,
                null,
                1,
                grantTime,
                grantTime);
        return new AdministrativeAuthorizationService(
                (requestedTenant, actorIdentityId, permission) ->
                        requestedTenant.equals(tenant)
                                && actorIdentityId.equals(actorIdentity.id())
                                && permission.equals(allowedPermission)
                                ? List.of(grant)
                                : List.of(),
                (requestedTenant, actorIdentityId) ->
                        requestedTenant.equals(tenant)
                                && actorIdentityId.equals(actorIdentity.id())
                                && identities.findById(
                                                requestedTenant,
                                                actorIdentityId)
                                        .map(identity ->
                                                identity.lifecycleState()
                                                        == IdentityLifecycleState.ACTIVE)
                                        .orElse(false));
    }

    private AdministrativeAuthorizationService authorization(boolean allow) {
        Instant grantTime = Instant.parse("2026-01-01T00:00:00Z");
        AdministrativeGrant grant = new AdministrativeGrant(
                ids.nextId(),
                actorIdentity.id(),
                ids.nextId(),
                new AdministrativeScope(AdministrativeScopeType.GLOBAL, null, null),
                AdministrativeGrantState.ACTIVE,
                grantTime,
                null,
                1,
                grantTime,
                grantTime);
        return new AdministrativeAuthorizationService(
                (requestedTenant, actorIdentityId, permission) -> allow ? List.of(grant) : List.of(),
                (requestedTenant, actorIdentityId) -> requestedTenant.equals(tenant)
                        && actorIdentityId.equals(actorIdentity.id())
                        && identities.findById(requestedTenant, actorIdentityId)
                                .map(identity -> identity.lifecycleState() == IdentityLifecycleState.ACTIVE)
                                .orElse(false));
    }
}
