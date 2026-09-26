package io.wyrmgate.iam.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.RoleCommandService;
import io.wyrmgate.iam.catalog.application.RoleQueryService;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogRepository;
import io.wyrmgate.iam.catalog.persistence.JdbcRoleExpansionFactSink;
import io.wyrmgate.iam.catalog.persistence.JdbcRoleRepository;
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

class CatalogRoleApiIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final String ACTOR_ATTRIBUTE =
            ControlPlaneActorRequestContext.class.getName() + ".actor";
    private static final Instant NOW =
            Instant.parse("2026-09-26T07:45:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcCatalogRepository catalogRepository;
    private static JdbcRoleRepository roleRepository;
    private static CatalogCommandService catalog;
    private static RoleCommandService roles;
    private static RoleQueryService queries;
    private static JdbcIdempotencyRepository idempotency;
    private static TransactionExecutor transactions;

    private final ObjectMapper json = new ObjectMapper();
    private TenantContext tenant;
    private AuthenticatedAdministrativeActor actor;
    private MockMvc authorized;

    @BeforeAll
    static void startPostgres() {
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
        catalogRepository = new JdbcCatalogRepository(jdbc);
        roleRepository = new JdbcRoleRepository(jdbc);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        catalog = new CatalogCommandService(
                catalogRepository, ids, transactions);
        roles = new RoleCommandService(
                catalogRepository,
                roleRepository,
                new JdbcRoleExpansionFactSink(
                        new JdbcOutboxRepository(jdbc), ids),
                ids,
                transactions);
        queries = new RoleQueryService(roleRepository);
        idempotency = new JdbcIdempotencyRepository(jdbc, ids);
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE
                    catalog.role_version_member,
                    catalog.role_version,
                    catalog.role,
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    platform.outbox_event,
                    platform.idempotency_record,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create("Catalog Role API", NOW).id());
        actor = new AuthenticatedAdministrativeActor(
                tenant, ids.nextId());
        authorized = mockMvc(authorization(true));
    }

    @Test
    void roleAndVersionLifecycleFollowGovernedHttpContract()
            throws Exception {
        var app = catalog.createApplication(
                tenant, "erp", "ERP", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant,
                app.id(),
                target.id(),
                "finance-read",
                "finance-read",
                "GROUP",
                NOW);

        String roleLocation = authorized.perform(
                        post("/api/v1/roles")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "role-idem-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "roleType":"APPLICATION",
                                          "applicationId":"%s",
                                          "code":"finance-user",
                                          "name":"Finance User"
                                        }
                                        """.formatted(app.id())))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string("ETag", "\"rev-1\""))
                .andExpect(
                        jsonPath("$.roleType")
                                .value("APPLICATION"))
                .andExpect(
                        jsonPath("$.applicationId")
                                .value(app.id().toString()))
                .andReturn()
                .getResponse()
                .getHeader("Location");
        UUID roleId = UUID.fromString(
                roleLocation.substring(
                        roleLocation.lastIndexOf('/') + 1));

        authorized.perform(
                        post("/api/v1/roles")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "role-idem-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "roleType":"APPLICATION",
                                          "applicationId":"%s",
                                          "code":"finance-user",
                                          "name":"Finance User"
                                        }
                                        """.formatted(app.id())))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(roleId.toString()));

        authorized.perform(
                        patch("/api/v1/roles/{id}", roleId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "role-idem-0002")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"Finance Standard User\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\"rev-2\""))
                .andExpect(
                        jsonPath("$.name")
                                .value("Finance Standard User"));

        String versionLocation = authorized.perform(
                        post("/api/v1/roles/{id}/versions", roleId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "role-version-idem-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "members":[
                                            {
                                              "kind":"ENTITLEMENT",
                                              "entitlementId":"%s"
                                            }
                                          ]
                                        }
                                        """.formatted(
                                        entitlement.id())))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string("ETag", "\"rev-1\""))
                .andExpect(
                        jsonPath("$.state")
                                .value("DRAFT"))
                .andExpect(
                        jsonPath("$.members[0].kind")
                                .value("ENTITLEMENT"))
                .andExpect(
                        jsonPath("$.members[0].entitlementId")
                                .value(entitlement.id().toString()))
                .andReturn()
                .getResponse()
                .getHeader("Location");
        UUID versionId = UUID.fromString(
                versionLocation.substring(
                        versionLocation.lastIndexOf('/') + 1));

        authorized.perform(
                        post("/api/v1/roles/{roleId}/versions/{versionId}/validate",
                                roleId, versionId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "role-version-idem-0002"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\"rev-2\""))
                .andExpect(
                        jsonPath("$.state")
                                .value("READY"));

        authorized.perform(
                        post("/api/v1/roles/{roleId}/versions/{versionId}/activate",
                                roleId, versionId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-2\"")
                                .header(
                                        "Idempotency-Key",
                                        "role-version-idem-0003"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\"rev-3\""))
                .andExpect(
                        jsonPath("$.state")
                                .value("ACTIVE"))
                .andExpect(
                        jsonPath("$.activatedAt")
                                .isString());

        authorized.perform(
                        get("/api/v1/roles/{roleId}/versions/{versionId}",
                                roleId, versionId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\"rev-3\""))
                .andExpect(
                        jsonPath("$.contentHash")
                                .isString())
                .andExpect(
                        jsonPath("$.members.length()")
                                .value(1));

        authorized.perform(
                        post("/api/v1/roles/{id}/retire", roleId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-2\"")
                                .header(
                                        "Idempotency-Key",
                                        "role-idem-0003"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\"rev-3\""))
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("RETIRED"));
    }

    @Test
    void versionActivationSupersedesPriorVersionAndStaleWritesFail()
            throws Exception {
        var app = catalog.createApplication(
                tenant, "erp", "ERP", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var first = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "read", "read", "GROUP", NOW);
        var second = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "write", "write", "GROUP", NOW);

        UUID roleId = createApplicationRole(app.id());
        UUID v1 = createVersion(roleId, first.id(), "v1-create");
        validateAndActivate(roleId, v1, "v1");

        UUID v2 = createVersion(roleId, second.id(), "v2-create");

        authorized.perform(
                        post("/api/v1/roles/{roleId}/versions/{versionId}/validate",
                                roleId, v2)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-9\"")
                                .header(
                                        "Idempotency-Key",
                                        "v2-stale-validate"))
                .andExpect(
                        status().isPreconditionFailed())
                .andExpect(
                        jsonPath("$.code")
                                .value("stale_revision"));

        authorized.perform(
                        post("/api/v1/roles/{roleId}/versions/{versionId}/validate",
                                roleId, v2)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "v2-validate-ok"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\"rev-2\""));

        authorized.perform(
                        post("/api/v1/roles/{roleId}/versions/{versionId}/activate",
                                roleId, v2)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-2\"")
                                .header(
                                        "Idempotency-Key",
                                        "v2-activate-ok"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\"rev-3\""));

        authorized.perform(
                        get("/api/v1/roles/{roleId}/versions/{versionId}",
                                roleId, v1)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("ETag", "\"rev-4\""))
                .andExpect(
                        jsonPath("$.state")
                                .value("SUPERSEDED"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM catalog.role_version
                WHERE tenant_id = ?
                  AND role_id = ?
                  AND state = 'ACTIVE'
                """,
                Integer.class,
                tenant.tenantId(),
                roleId))
                .isEqualTo(1);
    }

    @Test
    void roleVersionCursorIsBoundToRoleAndIdempotencyConflicts()
            throws Exception {
        var app = catalog.createApplication(
                tenant, "erp", "ERP", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "read", "read", "GROUP", NOW);

        UUID firstRole = createApplicationRole(app.id());
        createVersion(firstRole, entitlement.id(), "first-v1");
        createVersion(firstRole, entitlement.id(), "first-v2");

        UUID secondRole = UUID.fromString(
                authorized.perform(
                                post("/api/v1/roles")
                                        .requestAttr(
                                                ACTOR_ATTRIBUTE,
                                                actor)
                                        .header(
                                                "Idempotency-Key",
                                                "business-role-create")
                                        .contentType(
                                                MediaType.APPLICATION_JSON)
                                        .content("""
                                                {
                                                  "roleType":"BUSINESS",
                                                  "applicationId":null,
                                                  "code":"employee",
                                                  "name":"Employee"
                                                }
                                                """))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getHeader("Location")
                        .replaceFirst(".*/", ""));

        String body = authorized.perform(
                        get("/api/v1/roles/{roleId}/versions",
                                firstRole)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.nextCursor")
                                .isString())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode node = json.readTree(body);
        String cursor = node.get("nextCursor").asText();

        authorized.perform(
                        get("/api/v1/roles/{roleId}/versions",
                                secondRole)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("validation_failed"));

        authorized.perform(
                        post("/api/v1/roles")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "business-role-create")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "roleType":"BUSINESS",
                                          "applicationId":null,
                                          "code":"different",
                                          "name":"Different"
                                        }
                                        """))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code")
                                .value("idempotency_conflict"));
    }

    @Test
    void authorizationDefaultsToDeny() throws Exception {
        MockMvc denied = mockMvc(authorization(false));

        denied.perform(
                        get("/api/v1/roles")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.code")
                                .value("forbidden"));

        denied.perform(
                        post("/api/v1/roles")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "denied-role-create")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "roleType":"BUSINESS",
                                          "applicationId":null,
                                          "code":"employee",
                                          "name":"Employee"
                                        }
                                        """))
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.code")
                                .value("forbidden"));
    }

    private UUID createApplicationRole(UUID applicationId)
            throws Exception {
        String location = authorized.perform(
                        post("/api/v1/roles")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key",
                                        "application-role-"
                                                + ids.nextId())
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "roleType":"APPLICATION",
                                          "applicationId":"%s",
                                          "code":"role-%s",
                                          "name":"Application Role"
                                        }
                                        """.formatted(
                                        applicationId,
                                        ids.nextId())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        return UUID.fromString(
                location.substring(location.lastIndexOf('/') + 1));
    }

    private UUID createVersion(
            UUID roleId,
            UUID entitlementId,
            String key) throws Exception {
        String location = authorized.perform(
                        post("/api/v1/roles/{roleId}/versions",
                                roleId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "Idempotency-Key", key)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "members":[
                                            {
                                              "kind":"ENTITLEMENT",
                                              "entitlementId":"%s"
                                            }
                                          ]
                                        }
                                        """.formatted(entitlementId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        return UUID.fromString(
                location.substring(location.lastIndexOf('/') + 1));
    }

    private void validateAndActivate(
            UUID roleId,
            UUID versionId,
            String prefix) throws Exception {
        authorized.perform(
                        post("/api/v1/roles/{roleId}/versions/{versionId}/validate",
                                roleId, versionId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        prefix + "-validate"))
                .andExpect(status().isOk());
        authorized.perform(
                        post("/api/v1/roles/{roleId}/versions/{versionId}/activate",
                                roleId, versionId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header("If-Match", "\"rev-2\"")
                                .header(
                                        "Idempotency-Key",
                                        prefix + "-activate"))
                .andExpect(status().isOk());
    }

    private MockMvc mockMvc(
            AdministrativeAuthorizationService authorization) {
        CatalogRoleApiMutationService mutations =
                new CatalogRoleApiMutationService(
                        authorization,
                        roles,
                        roleRepository,
                        idempotency,
                        transactions);
        CatalogRoleController controller =
                new CatalogRoleController(
                        queries,
                        mutations,
                        authorization,
                        ids,
                        testCursorCodec());
        return MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(
                        new CatalogApiErrorHandler(ids))
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

    private CatalogCursorCodec testCursorCodec() {
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial(
                            "catalog-role-api-test",
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
                                signature.initSign(pair.getPrivate());
                                signature.update(payload);
                                return signature.sign();
                            } catch (Exception error) {
                                throw new IllegalStateException(
                                        error);
                            }
                        }
                    };
            return new CatalogCursorCodec(
                    provider,
                    Duration.ofMinutes(15),
                    Clock.systemUTC());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
