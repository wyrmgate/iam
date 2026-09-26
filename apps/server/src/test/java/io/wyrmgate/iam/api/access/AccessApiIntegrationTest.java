package io.wyrmgate.iam.api.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.AccessAssignmentCommandService;
import io.wyrmgate.iam.access.application.AccessAssignmentQueryService;
import io.wyrmgate.iam.access.application.DesiredStateDerivationService;
import io.wyrmgate.iam.access.application.EffectiveAccessProcessingService;
import io.wyrmgate.iam.access.application.EffectiveAccessQueryService;
import io.wyrmgate.iam.access.application.EffectiveAccessReadService;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentBoundaryScheduler;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentFactSink;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentRepository;
import io.wyrmgate.iam.access.persistence.JdbcDesiredStateProjectionRepository;
import io.wyrmgate.iam.access.persistence.JdbcEffectiveAccessRepository;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.application.RoleCommandService;
import io.wyrmgate.iam.catalog.application.RoleExpansionQueryService;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogRepository;
import io.wyrmgate.iam.catalog.persistence.JdbcRoleExpansionFactSink;
import io.wyrmgate.iam.catalog.persistence.JdbcRoleRepository;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQueryService;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityFactSink;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalRepository;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
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
import java.time.ZoneOffset;
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

class AccessApiIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final String ACTOR_ATTRIBUTE =
            ControlPlaneActorRequestContext.class.getName() + ".actor";
    private static final Instant NOW =
            Instant.parse("2026-09-26T11:50:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;
    private static JdbcOutboxRepository outbox;
    private static JdbcScheduledWorkRepository scheduledWork;
    private static JdbcIdempotencyRepository idempotency;

    private static IdentityCommandService identities;
    private static CatalogCommandService catalog;
    private static RoleCommandService roles;
    private static RoleExpansionQueryService expansion;
    private static JdbcAccessAssignmentRepository assignmentRepository;
    private static JdbcEffectiveAccessRepository effectiveRepository;
    private static AccessAssignmentCommandService assignmentCommands;
    private static AccessAssignmentQueryService assignmentQueries;
    private static EffectiveAccessReadService effectiveReads;
    private static DesiredStateDerivationService desiredDerivation;

    private final ObjectMapper json = new ObjectMapper();
    private TenantContext tenant;
    private AuthenticatedAdministrativeActor actor;
    private MockMvc authorized;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .load();
        flyway.migrate();
        flyway.validate();
        assertThat(
                flyway.info()
                        .current()
                        .getVersion()
                        .getVersion())
                .isEqualTo("25");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        outbox = new JdbcOutboxRepository(jdbc);
        scheduledWork =
                new JdbcScheduledWorkRepository(jdbc, ids);
        idempotency = new JdbcIdempotencyRepository(jdbc, ids);

        var identityRepository =
                new JdbcIdentityRepository(jdbc);
        IdentityFactSink noIdentityFacts =
                new IdentityFactSink() {
                    @Override
                    public void identityCreated(
                            TenantContext tenant,
                            Identity identity,
                            UUID correlationId,
                            UUID causationId) {
                    }

                    @Override
                    public void displayNameChanged(
                            TenantContext tenant,
                            Identity identity,
                            UUID correlationId,
                            UUID causationId) {
                    }
                };
        identities = new IdentityCommandService(
                identityRepository,
                noIdentityFacts,
                ids,
                transactions);

        var catalogRepository =
                new JdbcCatalogRepository(jdbc);
        var roleRepository = new JdbcRoleRepository(jdbc);
        catalog = new CatalogCommandService(
                catalogRepository, ids, transactions);
        roles = new RoleCommandService(
                catalogRepository,
                roleRepository,
                new JdbcRoleExpansionFactSink(outbox, ids),
                ids,
                transactions);
        expansion = new RoleExpansionQueryService(
                catalogRepository, roleRepository);

        var principalRepository =
                new JdbcPrincipalRepository(jdbc);
        var identityReferences =
                new IdentityAccessReferenceQueryService(
                        identityRepository,
                        principalRepository);
        CatalogQueryService catalogQueries =
                new CatalogQueryService(catalogRepository);

        assignmentRepository =
                new JdbcAccessAssignmentRepository(jdbc);
        effectiveRepository =
                new JdbcEffectiveAccessRepository(jdbc, ids);
        assignmentCommands =
                new AccessAssignmentCommandService(
                        assignmentRepository,
                        identityReferences,
                        catalogQueries,
                        expansion,
                        new JdbcAccessAssignmentFactSink(
                                outbox, ids),
                        new JdbcAccessAssignmentBoundaryScheduler(
                                scheduledWork),
                        ids,
                        transactions);
        assignmentQueries =
                new AccessAssignmentQueryService(
                        assignmentRepository);
        effectiveReads =
                new EffectiveAccessReadService(
                        effectiveRepository);

        var desiredRepository =
                new JdbcDesiredStateProjectionRepository(
                        jdbc, ids);
        desiredDerivation =
                new DesiredStateDerivationService(
                        new EffectiveAccessQueryService(
                                effectiveRepository),
                        desiredRepository,
                        catalogQueries,
                        identityReferences,
                        (tenant, state) -> { },
                        transactions);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE
                    access.effective_access_support_role_version,
                    access.effective_access_support,
                    access.effective_access,
                    access.access_assignment,
                    access.desired_grant_state,
                    access.desired_principal_state,
                    platform.scheduled_work,
                    platform.outbox_event,
                    platform.idempotency_record,
                    identity.principal,
                    identity.person_profile,
                    identity.service_profile,
                    identity.workload_profile,
                    identity.identity,
                    catalog.role_version_member,
                    catalog.role_version,
                    catalog.role,
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create("Access API", NOW).id());
        actor = new AuthenticatedAdministrativeActor(
                tenant, ids.nextId());
        authorized = mockMvc(authorization(true));
    }

    @Test
    void entitlementAssignmentLifecycleAndEffectiveReadAreSemantic()
            throws Exception {
        Identity identity = identity("Ada");
        var app = catalog.createApplication(
                tenant, "app", "App", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant,
                app.id(),
                target.id(),
                "read",
                "Read",
                "GROUP",
                NOW);

        String location = authorized.perform(
                        post("/api/v1/access-assignments")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "assignment-create-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "identityId":"%s",
                                          "targetKind":"ENTITLEMENT",
                                          "roleId":null,
                                          "entitlementId":"%s",
                                          "principalConstraintKind":"ANY",
                                          "specificPrincipalId":null,
                                          "validFrom":null,
                                          "validUntil":null
                                        }
                                        """.formatted(
                                        identity.id(),
                                        entitlement.id())))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string("ETag", "\\\"rev-1\\\""))
                .andExpect(
                        jsonPath("$.provenanceKind")
                                .value("MANUAL"))
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getHeader("Location");
        UUID assignmentId = UUID.fromString(
                location.substring(
                        location.lastIndexOf('/') + 1));

        authorized.perform(
                        post("/api/v1/access-assignments")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "assignment-create-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "identityId":"%s",
                                          "targetKind":"ENTITLEMENT",
                                          "roleId":null,
                                          "entitlementId":"%s",
                                          "principalConstraintKind":"ANY",
                                          "specificPrincipalId":null,
                                          "validFrom":null,
                                          "validUntil":null
                                        }
                                        """.formatted(
                                        identity.id(),
                                        entitlement.id())))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(assignmentId.toString()));

        processor(NOW.plusSeconds(1)).processAvailable();

        String page = authorized.perform(
                        get("/api/v1/effective-access")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .param(
                                        "identityId",
                                        identity.id().toString()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items.length()")
                                .value(1))
                .andExpect(
                        jsonPath("$.items[0].supportCount")
                                .value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID effectiveId = UUID.fromString(
                json.readTree(page)
                        .get("items")
                        .get(0)
                        .get("id")
                        .asText());

        authorized.perform(
                        get("/api/v1/effective-access/{id}",
                                effectiveId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.supports[0].accessAssignmentId")
                                .value(
                                        assignmentId.toString()))
                .andExpect(
                        jsonPath("$.supports[0].pathDepth")
                                .value(0))
                .andExpect(
                        jsonPath(
                                "$.supports[0].roleVersionPath.length()")
                                .value(0));

        authorized.perform(
                        post("/api/v1/access-assignments/{id}/suspend",
                                assignmentId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "If-Match",
                                        "\\\"rev-1\\\"")
                                .header(
                                        "Idempotency-Key",
                                        "assignment-suspend-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\\\"rev-2\\\""))
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("SUSPENDED"));

        authorized.perform(
                        get("/api/v1/effective-access/{id}",
                                effectiveId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isNotFound());

        authorized.perform(
                        post("/api/v1/access-assignments/{id}/resume",
                                assignmentId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "If-Match",
                                        "\\\"rev-2\\\"")
                                .header(
                                        "Idempotency-Key",
                                        "assignment-resume-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\\\"rev-3\\\""))
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("ACTIVE"));

        authorized.perform(
                        get("/api/v1/effective-access/{id}",
                                effectiveId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk());

        authorized.perform(
                        post("/api/v1/access-assignments/{id}/revoke",
                                assignmentId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "If-Match",
                                        "\\\"rev-3\\\"")
                                .header(
                                        "Idempotency-Key",
                                        "assignment-revoke-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\\\"rev-4\\\""))
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("REVOKED"));

        authorized.perform(
                        get("/api/v1/effective-access/{id}",
                                effectiveId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isNotFound());

        authorized.perform(
                        post("/api/v1/access-assignments/{id}/suspend",
                                assignmentId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "If-Match",
                                        "\\\"rev-1\\\"")
                                .header(
                                        "Idempotency-Key",
                                        "assignment-stale-0001"))
                .andExpect(
                        status().isPreconditionFailed())
                .andExpect(
                        jsonPath("$.code")
                                .value("stale_revision"));
    }

    @Test
    void roleSupportPathAndEffectiveCursorContextAreExposed()
            throws Exception {
        Identity identity = identity("Role User");
        var app = catalog.createApplication(
                tenant, "app", "App", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var first = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "first", "First", "GROUP", NOW);
        var second = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "second", "Second", "GROUP", NOW);

        Role role = roles.createRole(
                tenant,
                Role.RoleType.APPLICATION,
                app.id(),
                "users",
                "Users",
                NOW);
        var version = roles.createDraftVersion(
                tenant,
                role.id(),
                List.of(
                        RoleCommandService.MemberSpec.entitlement(
                                first.id())),
                NOW.plusSeconds(1));
        roles.markReady(
                tenant,
                version.id(),
                1,
                NOW.plusSeconds(2));
        var active = roles.activate(
                tenant,
                version.id(),
                2,
                NOW.plusSeconds(3));

        UUID roleAssignment = createAssignment(
                identity.id(),
                "ROLE",
                role.id(),
                null,
                "role-assignment-0001");
        createAssignment(
                identity.id(),
                "ENTITLEMENT",
                null,
                second.id(),
                "direct-assignment-0001");

        processor(NOW.plusSeconds(5)).processAvailable();

        String page = authorized.perform(
                        get("/api/v1/effective-access")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .param(
                                        "identityId",
                                        identity.id().toString())
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.nextCursor")
                                .isString())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode parsed = json.readTree(page);
        String cursor =
                parsed.get("nextCursor").asText();

        authorized.perform(
                        get("/api/v1/effective-access")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("validation_failed"));

        UUID roleEffectiveId = jdbc.queryForObject("""
                SELECT ea.id
                FROM access.effective_access ea
                JOIN access.effective_access_support s
                  ON s.tenant_id = ea.tenant_id
                 AND s.effective_access_id = ea.id
                WHERE ea.tenant_id = ?
                  AND s.access_assignment_id = ?
                """,
                UUID.class,
                tenant.tenantId(),
                roleAssignment);

        authorized.perform(
                        get("/api/v1/effective-access/{id}",
                                roleEffectiveId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.supports[0].pathDepth")
                                .value(1))
                .andExpect(
                        jsonPath(
                                "$.supports[0].roleVersionPath[0]")
                                .value(active.id().toString()));
    }

    @Test
    void scheduledAssignmentCanBeCancelledAndControlPlaneDefaultsToDeny()
            throws Exception {
        Identity identity = identity("Scheduled");
        var app = catalog.createApplication(
                tenant, "app", "App", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "read", "Read", "GROUP", NOW);

        String location = authorized.perform(
                        post("/api/v1/access-assignments")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "scheduled-create-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "identityId":"%s",
                                          "targetKind":"ENTITLEMENT",
                                          "roleId":null,
                                          "entitlementId":"%s",
                                          "principalConstraintKind":"ANY",
                                          "specificPrincipalId":null,
                                          "validFrom":"%s",
                                          "validUntil":null
                                        }
                                        """.formatted(
                                        identity.id(),
                                        entitlement.id(),
                                        NOW.plusSeconds(3600))))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("SCHEDULED"))
                .andReturn()
                .getResponse()
                .getHeader("Location");
        UUID assignmentId = UUID.fromString(
                location.substring(
                        location.lastIndexOf('/') + 1));

        authorized.perform(
                        post("/api/v1/access-assignments/{id}/cancel",
                                assignmentId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "If-Match",
                                        "\\\"rev-1\\\"")
                                .header(
                                        "Idempotency-Key",
                                        "scheduled-cancel-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("CANCELLED"));

        MockMvc denied = mockMvc(authorization(false));
        denied.perform(
                        get("/api/v1/access-assignments")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.code")
                                .value("forbidden"));

        denied.perform(
                        get("/api/v1/effective-access")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.code")
                                .value("forbidden"));
    }

    private UUID createAssignment(
            UUID identityId,
            String targetKind,
            UUID roleId,
            UUID entitlementId,
            String key) throws Exception {
        String location = authorized.perform(
                        post("/api/v1/access-assignments")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key", key)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "identityId":"%s",
                                          "targetKind":"%s",
                                          "roleId":%s,
                                          "entitlementId":%s,
                                          "principalConstraintKind":"ANY",
                                          "specificPrincipalId":null,
                                          "validFrom":null,
                                          "validUntil":null
                                        }
                                        """.formatted(
                                        identityId,
                                        targetKind,
                                        roleId == null
                                                ? "null"
                                                : "\\\"" + roleId + "\\\"",
                                        entitlementId == null
                                                ? "null"
                                                : "\\\"" + entitlementId + "\\\"")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        return UUID.fromString(
                location.substring(
                        location.lastIndexOf('/') + 1));
    }

    private EffectiveAccessProcessingService processor(
            Instant at) {
        return new EffectiveAccessProcessingService(
                outbox,
                scheduledWork,
                assignmentRepository,
                effectiveRepository,
                expansion,
                desiredDerivation,
                Clock.fixed(at, ZoneOffset.UTC));
    }

    private Identity identity(String displayName) {
        return identities.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                displayName,
                NOW,
                ids.nextId(),
                null);
    }

    private MockMvc mockMvc(
            AdministrativeAuthorizationService authorization) {
        AccessApiMutationService mutations =
                new AccessApiMutationService(
                        authorization,
                        assignmentCommands,
                        assignmentRepository,
                        idempotency,
                        transactions);
        AccessController controller =
                new AccessController(
                        assignmentQueries,
                        effectiveReads,
                        mutations,
                        authorization,
                        ids,
                        testCursorCodec());
        return MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(
                        new AccessApiErrorHandler(ids))
                .build();
    }

    private AdministrativeAuthorizationService authorization(
            boolean allow) {
        Instant created =
                Instant.parse("2026-01-01T00:00:00Z");
        AdministrativeGrant grant =
                new AdministrativeGrant(
                        ids.nextId(),
                        actor.identityId(),
                        ids.nextId(),
                        new AdministrativeScope(
                                AdministrativeScopeType.GLOBAL,
                                null,
                                null),
                        AdministrativeGrantState.ACTIVE,
                        created,
                        null,
                        1,
                        created,
                        created);
        return new AdministrativeAuthorizationService(
                (requestedTenant, identityId, permission) ->
                        allow
                                && requestedTenant.equals(tenant)
                                && identityId.equals(
                                        actor.identityId())
                                ? List.of(grant)
                                : List.of(),
                (requestedTenant, identityId) ->
                        requestedTenant.equals(tenant)
                                && identityId.equals(
                                        actor.identityId()));
    }

    private AccessCursorCodec testCursorCodec() {
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial(
                            "access-api-test",
                            "SHA256withRSA",
                            pair.getPublic());
            SigningKeyProvider provider =
                    new SigningKeyProvider() {
                        @Override
                        public SigningKeyMaterial
                                currentSigningKey() {
                            return material;
                        }

                        @Override
                        public Optional<SigningKeyMaterial>
                                verificationKey(String keyId) {
                            return material.keyId().equals(keyId)
                                    ? Optional.of(material)
                                    : Optional.empty();
                        }

                        @Override
                        public byte[] sign(byte[] payload) {
                            try {
                                Signature signature =
                                        Signature.getInstance(
                                                material.signingAlgorithm());
                                signature.initSign(
                                        pair.getPrivate());
                                signature.update(payload);
                                return signature.sign();
                            } catch (Exception error) {
                                throw new IllegalStateException(
                                        error);
                            }
                        }
                    };
            return new AccessCursorCodec(
                    provider,
                    Duration.ofMinutes(15),
                    Clock.systemUTC());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
