package io.wyrmgate.iam.api.catalog;

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
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogRepository;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
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

class CatalogApiIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final String ACTOR_ATTRIBUTE =
            ControlPlaneActorRequestContext.class.getName() + ".actor";

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcCatalogRepository repository;
    private static CatalogCommandService commands;
    private static CatalogQueryService queries;
    private static JdbcIdempotencyRepository idempotency;
    private static TransactionExecutor transactions;

    private TenantContext tenant;
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
        repository = new JdbcCatalogRepository(jdbc);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        commands = new CatalogCommandService(repository, ids, transactions);
        queries = new CatalogQueryService(repository);
        idempotency = new JdbcIdempotencyRepository(jdbc, ids);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("18");
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    platform.idempotency_record,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create("Catalog API", Instant.parse("2026-09-21T09:10:00Z")).id());
        actor = new AuthenticatedAdministrativeActor(tenant, ids.nextId());
        authorized = mockMvc(authorization(true));
    }

    @Test
    void createReadListRenameTargetEntitlementAndRetireFollowContract() throws Exception {
        String location = authorized.perform(post("/api/v1/applications")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "idem-00000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"erp\",\"name\":\"ERP\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"rev-1\""))
                .andExpect(jsonPath("$.code").value("erp"))
                .andExpect(jsonPath("$.lifecycleState").value("ACTIVE"))
                .andReturn().getResponse().getHeader("Location");
        UUID appId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        authorized.perform(post("/api/v1/applications")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "idem-00000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"erp\",\"name\":\"ERP\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(appId.toString()));

        authorized.perform(post("/api/v1/applications")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "idem-00000002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"hr\",\"name\":\"HR\"}"))
                .andExpect(status().isCreated());

        String cursor = authorized.perform(get("/api/v1/applications")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(cursor).contains("nextCursor");

        authorized.perform(patch("/api/v1/applications/{id}", appId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "idem-00000003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ERP Platform\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"rev-2\""))
                .andExpect(jsonPath("$.name").value("ERP Platform"));

        String targetLocation = authorized.perform(post("/api/v1/applications/{id}/targets", appId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "idem-00000004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"prod\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationId").value(appId.toString()))
                .andReturn().getResponse().getHeader("Location");
        UUID targetId = UUID.fromString(
                targetLocation.substring(targetLocation.lastIndexOf('/') + 1));

        String entitlementLocation = authorized.perform(
                        post("/api/v1/applications/{id}/entitlements", appId)
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header("Idempotency-Key", "idem-00000005")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "applicationTargetId":"%s",
                                          "code":"finance-read",
                                          "nativeKey":"scim-group-77",
                                          "entitlementType":"GROUP"
                                        }
                                        """.formatted(targetId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationTargetId").value(targetId.toString()))
                .andExpect(jsonPath("$.nativeKey").value("scim-group-77"))
                .andReturn().getResponse().getHeader("Location");
        UUID entitlementId = UUID.fromString(
                entitlementLocation.substring(entitlementLocation.lastIndexOf('/') + 1));

        authorized.perform(get("/api/v1/entitlements/{id}", entitlementId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"rev-1\""));

        authorized.perform(post("/api/v1/entitlements/{id}/retire", entitlementId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "idem-00000006"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("RETIRED"))
                .andExpect(header().string("ETag", "\"rev-2\""));

        authorized.perform(post("/api/v1/application-targets/{id}/retire", targetId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "idem-00000007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("RETIRED"));

        authorized.perform(post("/api/v1/applications/{id}/retire", appId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-2\"")
                        .header("Idempotency-Key", "idem-00000008"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("RETIRED"));
    }

    @Test
    void staleRevisionIdempotencyConflictAndCursorContextAreRejected() throws Exception {
        String location = authorized.perform(post("/api/v1/applications")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "idem-00000010")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"erp\",\"name\":\"ERP\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        UUID appId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        authorized.perform(post("/api/v1/applications")
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("Idempotency-Key", "idem-00000010")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"erp2\",\"name\":\"Different\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_conflict"));

        authorized.perform(patch("/api/v1/applications/{id}", appId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "idem-00000011")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ERP 2\"}"))
                .andExpect(status().isOk());

        authorized.perform(patch("/api/v1/applications/{id}", appId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .header("If-Match", "\"rev-1\"")
                        .header("Idempotency-Key", "idem-00000012")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Stale\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("stale_revision"));

        commands.createTarget(tenant, appId, "prod", Instant.now());
        commands.createTarget(tenant, appId, "dev", Instant.now().plusMillis(1));
        String targetPage = authorized.perform(get("/api/v1/applications/{id}/targets", appId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String cursor = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(targetPage).get("nextCursor").asText();

        authorized.perform(get("/api/v1/applications/{id}/entitlements", appId)
                        .requestAttr(ACTOR_ATTRIBUTE, actor)
                        .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void authorizationDefaultsToDeny() throws Exception {
        MockMvc denied = mockMvc(authorization(false));
        denied.perform(get("/api/v1/applications")
                        .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    private MockMvc mockMvc(AdministrativeAuthorizationService authorization) {
        CatalogApiMutationService mutations = new CatalogApiMutationService(
                authorization, commands, repository, idempotency, transactions);
        CatalogController controller = new CatalogController(
                queries, mutations, authorization, ids, testCursorCodec());
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CatalogApiErrorHandler(ids))
                .build();
    }

    private AdministrativeAuthorizationService authorization(boolean allow) {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        AdministrativeGrant grant = new AdministrativeGrant(
                ids.nextId(), actor.identityId(), ids.nextId(),
                new AdministrativeScope(AdministrativeScopeType.GLOBAL, null, null),
                AdministrativeGrantState.ACTIVE,
                created, null, 1, created, created);
        return new AdministrativeAuthorizationService(
                (requestedTenant, identityId, permission) ->
                        allow && requestedTenant.equals(tenant)
                                && identityId.equals(actor.identityId())
                                ? List.of(grant) : List.of(),
                (requestedTenant, identityId) ->
                        requestedTenant.equals(tenant)
                                && identityId.equals(actor.identityId()));
    }

    private CatalogCursorCodec testCursorCodec() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial("catalog-api-test", "SHA256withRSA", pair.getPublic());
            SigningKeyProvider provider = new SigningKeyProvider() {
                @Override
                public SigningKeyMaterial currentSigningKey() {
                    return material;
                }

                @Override
                public Optional<SigningKeyMaterial> verificationKey(String keyId) {
                    return material.keyId().equals(keyId)
                            ? Optional.of(material) : Optional.empty();
                }

                @Override
                public byte[] sign(byte[] payload) {
                    try {
                        Signature signature = Signature.getInstance(material.signingAlgorithm());
                        signature.initSign(pair.getPrivate());
                        signature.update(payload);
                        return signature.sign();
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }
            };
            return new CatalogCursorCodec(
                    provider, Duration.ofMinutes(15), Clock.systemUTC());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
