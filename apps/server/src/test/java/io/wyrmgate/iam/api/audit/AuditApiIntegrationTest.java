package io.wyrmgate.iam.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.audit.application.AuditCommandService;
import io.wyrmgate.iam.audit.application.AuditQueryService;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.persistence.JdbcAuditRecordRepository;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;

class AuditApiIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final String ACTOR_ATTRIBUTE =
            ControlPlaneActorRequestContext.class.getName() + ".actor";
    private static final Instant NOW =
            Instant.parse("2026-10-01T05:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcAuditRecordRepository records;
    private static AuditCommandService commands;
    private static AuditQueryService queries;
    private static TransactionExecutor transactions;
    private static ObjectMapper json;

    private TenantContext tenant;
    private AuthenticatedAdministrativeActor actor;
    private AuditCursorCodec cursors;
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
                .isEqualTo("56");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        records = new JdbcAuditRecordRepository(jdbc);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        commands = new AuditCommandService(records, transactions, clock);
        queries = new AuditQueryService(records);
        json = new ObjectMapper().findAndRegisterModules();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE
                    audit.audit_record,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create("Audit API", NOW).id());
        actor = new AuthenticatedAdministrativeActor(
                tenant, ids.nextId());
        cursors = cursorCodec();
        authorized = mockMvc(AdministrativeScope.global());
    }

    @Test
    void searchIsExactBoundedDeterministicAndCursorBound() throws Exception {
        UUID correlation = ids.nextId();
        UUID firstId = append(
                tenant,
                NOW.minusSeconds(30),
                "identity:create",
                "identity",
                ids.nextId(),
                AuditOutcome.SUCCESS,
                correlation);
        UUID secondId = append(
                tenant,
                NOW.minusSeconds(20),
                "identity:create",
                "identity",
                ids.nextId(),
                AuditOutcome.DENIED,
                ids.nextId());
        append(
                tenant,
                NOW.minusSeconds(10),
                "connector:update",
                "connector",
                ids.nextId(),
                AuditOutcome.FAILURE,
                ids.nextId());

        String page = authorized.perform(
                        get("/api/v1/audit-records")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .param("actionType", "identity:create")
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(secondId.toString()))
                .andExpect(jsonPath("$.items[0].actionType").value("identity:create"))
                .andExpect(jsonPath("$.items[0].materialSnapshot").doesNotExist())
                .andExpect(jsonPath("$.items[0].integrityMetadata.schemaVersion").value("audit-integrity-v1"))
                .andExpect(jsonPath("$.items[0].integrityMetadata.algorithm").value("SHA-256"))
                .andExpect(jsonPath("$.items[0].integrityMetadata.contentSha256").isString())
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String cursor = json.readTree(page)
                .get("nextCursor")
                .asText();

        authorized.perform(
                        get("/api/v1/audit-records")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .param("actionType", "identity:create")
                                .param("limit", "1")
                                .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(firstId.toString()));

        authorized.perform(
                        get("/api/v1/audit-records")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .param("actionType", "connector:update")
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));

        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");
        authorized.perform(
                        get("/api/v1/audit-records")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .param("actionType", "identity:create")
                                .param("cursor", tampered))
                .andExpect(status().isBadRequest());

        authorized.perform(
                        get("/api/v1/audit-records")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .param("outcome", "SUCCESS")
                                .param("correlationId", correlation.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(firstId.toString()));
    }

    @Test
    void detailAuthorizationAndTenantIsolationFailClosed() throws Exception {
        assertThat(AdministrativePermissions.INITIAL_TENANT_ADMIN)
                .doesNotContain(
                        AdministrativePermissions.AUDIT_READ,
                        AdministrativePermissions.AUDIT_EXPORT);

        UUID recordId = append(
                tenant,
                NOW.minusSeconds(5),
                "role-version:activate",
                "role-version",
                ids.nextId(),
                AuditOutcome.SUCCESS,
                ids.nextId());

        authorized.perform(
                        get("/api/v1/audit-records/{id}", recordId)
                                .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recordId.toString()))
                .andExpect(jsonPath("$.outcome").value("SUCCESS"));

        MockMvc denied = mockMvc(null);
        denied.perform(
                        get("/api/v1/audit-records")
                                .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isForbidden());
        denied.perform(
                        get("/api/v1/audit-records/{id}", recordId)
                                .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isForbidden());

        MockMvc specific = mockMvc(
                AdministrativeScope.specificResource(
                        "audit", recordId));
        specific.perform(
                        get("/api/v1/audit-records/{id}", recordId)
                                .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk());
        specific.perform(
                        get("/api/v1/audit-records")
                                .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isForbidden());

        TenantContext other = new TenantContext(
                tenants.create("Other Audit API", NOW).id());
        UUID foreignId = append(
                other,
                NOW.minusSeconds(4),
                "identity:create",
                "identity",
                ids.nextId(),
                AuditOutcome.SUCCESS,
                ids.nextId());
        authorized.perform(
                        get("/api/v1/audit-records/{id}", foreignId)
                                .requestAttr(ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isNotFound());

        AuthenticatedAdministrativeActor otherActor =
                new AuthenticatedAdministrativeActor(
                        other, ids.nextId());
        MockMvc otherAuthorized = mockMvcFor(
                other,
                otherActor,
                AdministrativeScope.global());

        append(
                tenant,
                NOW.minusSeconds(3),
                "identity:update-metadata",
                "identity",
                ids.nextId(),
                AuditOutcome.SUCCESS,
                ids.nextId());

        String page = authorized.perform(
                        get("/api/v1/audit-records")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = json.readTree(page).get("nextCursor").asText();
        otherAuthorized.perform(
                        get("/api/v1/audit-records")
                                .requestAttr(ACTOR_ATTRIBUTE, otherActor)
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest());
    }

    private UUID append(
            TenantContext recordTenant,
            Instant occurredAt,
            String actionType,
            String resourceType,
            UUID resourceId,
            AuditOutcome outcome,
            UUID correlationId) {
        UUID id = ids.nextId();
        commands.append(
                recordTenant,
                new AuditRecordDraft(
                        id,
                        occurredAt,
                        actor == null ? null : actor.identityId(),
                        actionType,
                        resourceType,
                        resourceId,
                        outcome,
                        correlationId,
                        null));
        return id;
    }

    private MockMvc mockMvc(AdministrativeScope scope) {
        return mockMvcFor(tenant, actor, scope);
    }

    private MockMvc mockMvcFor(
            TenantContext authorizedTenant,
            AuthenticatedAdministrativeActor authorizedActor,
            AdministrativeScope scope) {
        AdministrativeAuthorizationService authorization =
                authorizationFor(
                        authorizedTenant,
                        authorizedActor,
                        scope);
        AuditController controller = new AuditController(
                queries,
                authorization,
                cursors,
                ids);
        return MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new AuditApiErrorHandler(ids))
                .build();
    }

    private AdministrativeAuthorizationService authorization(
            AdministrativeScope scope) {
        return authorizationFor(tenant, actor, scope);
    }

    private AdministrativeAuthorizationService authorizationFor(
            TenantContext authorizedTenant,
            AuthenticatedAdministrativeActor authorizedActor,
            AdministrativeScope scope) {
        if (scope == null) {
            return new AdministrativeAuthorizationService(
                    (requestedTenant, identityId, permission) -> List.of(),
                    (requestedTenant, identityId) -> true);
        }
        AdministrativeGrant grant = new AdministrativeGrant(
                ids.nextId(),
                authorizedActor.identityId(),
                ids.nextId(),
                scope,
                AdministrativeGrantState.ACTIVE,
                NOW.minusSeconds(60),
                null,
                1,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60));
        return new AdministrativeAuthorizationService(
                (requestedTenant, identityId, permission) ->
                        requestedTenant.equals(authorizedTenant)
                                        && identityId.equals(
                                                authorizedActor.identityId())
                                        && permission.equals(
                                                AdministrativePermissions.AUDIT_READ)
                                ? List.of(grant)
                                : List.of(),
                (requestedTenant, identityId) ->
                        requestedTenant.equals(authorizedTenant)
                                && identityId.equals(
                                        authorizedActor.identityId()));
    }

    private AuditCursorCodec cursorCodec() {
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial(
                            "audit-api-test",
                            "SHA256withRSA",
                            pair.getPublic());
            SigningKeyProvider provider =
                    new SigningKeyProvider() {
                        @Override
                        public SigningKeyMaterial currentSigningKey() {
                            return material;
                        }

                        @Override
                        public Optional<SigningKeyMaterial> verificationKey(
                                String keyId) {
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
                                throw new IllegalStateException(error);
                            }
                        }
                    };
            return new AuditCursorCodec(
                    provider,
                    Duration.ofMinutes(15),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
