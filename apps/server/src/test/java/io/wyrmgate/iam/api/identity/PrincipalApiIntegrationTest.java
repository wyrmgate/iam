package io.wyrmgate.iam.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.audit.application.AuditCommandService;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.persistence.JdbcAuditRecordRepository;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogRepository;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.identity.application.PrincipalQueryService;
import io.wyrmgate.iam.identity.application.PrincipalRepository;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalRepository;
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
import java.sql.Timestamp;
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

class PrincipalApiIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final String ACTOR_ATTRIBUTE =
            ControlPlaneActorRequestContext.class.getName()
                    + ".actor";
    private static final Instant NOW =
            Instant.parse("2026-09-30T02:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcIdentityRepository identityRepository;
    private static IdentityCommandService identities;
    private static PrincipalRepository principalRepository;
    private static PrincipalCommandService principals;
    private static PrincipalQueryService queries;
    private static JdbcIdempotencyRepository idempotency;
    private static TransactionExecutor transactions;
    private static ObjectMapper mapper;

    private TenantContext tenant;
    private Identity actorIdentity;
    private AuthenticatedAdministrativeActor actor;
    private IdentityCursorCodec cursors;
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
                .isEqualTo("50");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        JdbcOutboxRepository outbox =
                new JdbcOutboxRepository(jdbc);
        identityRepository =
                new JdbcIdentityRepository(jdbc);
        identities = new IdentityCommandService(
                identityRepository,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);
        principalRepository =
                new JdbcPrincipalRepository(jdbc);
        principals = new PrincipalCommandService(
                principalRepository,
                identityRepository,
                new CatalogQueryService(
                        new JdbcCatalogRepository(jdbc)),
                new JdbcPrincipalFactSink(outbox, ids),
                ids,
                transactions);
        queries = new PrincipalQueryService(
                principalRepository);
        idempotency = new JdbcIdempotencyRepository(
                jdbc, ids);
        mapper = new ObjectMapper();
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
                    platform.idempotency_record,
                    platform.outbox_event,
                    identity.principal,
                    identity.person_profile,
                    identity.service_profile,
                    identity.workload_profile,
                    identity.identity,
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create("Principal HTTP Tenant", NOW)
                        .id());
        actorIdentity = identity(
                tenant, "Principal Administrator");
        actor = new AuthenticatedAdministrativeActor(
                tenant, actorIdentity.id());
        cursors = newCursorCodec();
        authorized = mockMvc(authorization(true));
    }

    @Test
    void registerListReadCorrelateAndReplayFollowContract()
            throws Exception {
        UUID target = target(
                tenant, "erp", "prod");
        String body = """
                {"applicationTargetId":"%s","nativePrincipalKey":"employee-1001"}
                """.formatted(target);

        String location = authorized.perform(
                        post("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "ETag", "\"rev-1\""))
                .andExpect(jsonPath(
                        "$.identityId")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath(
                        "$.applicationTargetId")
                        .value(target.toString()))
                .andExpect(jsonPath("$.kind")
                        .value("ACCOUNT"))
                .andExpect(jsonPath(
                        "$.lifecycleState")
                        .value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        UUID principalId = UUID.fromString(
                location.substring(
                        location.lastIndexOf('/') + 1));

        authorized.perform(
                        post("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id")
                        .value(principalId.toString()));

        authorized.perform(
                        get("/api/v1/principals/{principalId}",
                                principalId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "ETag", "\"rev-1\""))
                .andExpect(jsonPath("$.id")
                        .value(principalId.toString()));

        principals.create(
                tenant,
                target,
                "employee-1002",
                null,
                NOW.plusSeconds(1),
                ids.nextId(),
                null);

        String pageJson = authorized.perform(
                        get("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()")
                        .value(1))
                .andExpect(jsonPath("$.nextCursor")
                        .isString())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = mapper.readTree(pageJson)
                .get("nextCursor")
                .asText();

        authorized.perform(
                        get("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .param("limit", "1")
                                .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()")
                        .value(1));

        Identity beneficiary = identity(
                tenant, "Ada");
        UUID correlationId = ids.nextId();
        String correlateBody = """
                {"identityId":"%s"}
                """.formatted(beneficiary.id());

        authorized.perform(
                        post("/api/v1/principals/{principalId}:correlate",
                                principalId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "X-Correlation-Id",
                                        correlationId)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000003")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(correlateBody))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "ETag", "\"rev-2\""))
                .andExpect(jsonPath("$.identityId")
                        .value(
                                beneficiary.id()
                                        .toString()))
                .andExpect(jsonPath("$.revision")
                        .value(2));

        authorized.perform(
                        post("/api/v1/principals/{principalId}:correlate",
                                principalId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000003")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(correlateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision")
                        .value(2));

        Integer correlatedFacts = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND aggregate_id = ?
                  AND event_type = 'identity.principal-correlated'
                """,
                Integer.class,
                tenant.tenantId(),
                principalId);
        Integer accessFacts = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND aggregate_id = ?
                  AND event_type = 'identity.principal-access-projection-input-changed'
                """,
                Integer.class,
                tenant.tenantId(),
                principalId);
        assertThat(correlatedFacts).isEqualTo(1);
        assertThat(accessFacts).isEqualTo(1);

        String factPayload = jdbc.queryForObject(
                """
                SELECT payload::text
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND aggregate_id = ?
                  AND event_type = 'identity.principal-correlated'
                """,
                String.class,
                tenant.tenantId(),
                principalId);
        assertThat(factPayload)
                .contains(target.toString())
                .doesNotContain("employee-1001")
                .doesNotContain(
                        beneficiary.id().toString());

        Integer registerSuccess = jdbc.queryForObject("""
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND actor_id = ?
                  AND action_type = 'principal:register'
                  AND resource_type = 'principal'
                  AND resource_id = ?
                  AND outcome = 'SUCCESS'
                """, Integer.class, tenant.tenantId(), actor.identityId(), principalId);
        assertThat(registerSuccess).isEqualTo(2);

        Integer correlateSuccess = jdbc.queryForObject("""
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND actor_id = ?
                  AND action_type = 'principal:correlate'
                  AND resource_id = ?
                  AND outcome = 'SUCCESS'
                """, Integer.class, tenant.tenantId(), actor.identityId(), principalId);
        assertThat(correlateSuccess).isEqualTo(2);

        Integer materialized = jdbc.queryForObject("""
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND resource_type = 'principal'
                  AND (material_snapshot IS NOT NULL OR integrity_metadata IS NOT NULL)
                """, Integer.class, tenant.tenantId());
        assertThat(materialized).isZero();
    }

    @Test
    void registrationRejectsDuplicateRetiredMissingAndExtraFields()
            throws Exception {
        UUID target = target(
                tenant, "hr", "prod");
        String body = """
                {"applicationTargetId":"%s","nativePrincipalKey":"native-1"}
                """.formatted(target);

        authorized.perform(
                        post("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000005")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated());

        authorized.perform(
                        post("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000006")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("principal_conflict"));

        authorized.perform(
                        post("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000005")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {"applicationTargetId":"%s","nativePrincipalKey":"different"}
                                        """.formatted(target)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("idempotency_conflict"));

        UUID missing = ids.nextId();
        authorized.perform(
                        post("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000008")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {"applicationTargetId":"%s","nativePrincipalKey":"missing-target"}
                                        """.formatted(missing)))
                .andExpect(status().isNotFound());

        jdbc.update("""
                UPDATE catalog.application_target
                SET lifecycle_state = 'RETIRED',
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ? AND id = ?
                """,
                Timestamp.from(NOW.plusSeconds(3)),
                tenant.tenantId(),
                target);

        authorized.perform(
                        post("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000009")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {"applicationTargetId":"%s","nativePrincipalKey":"retired-target"}
                                        """.formatted(target)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value(
                                "application_target_retired"));

        authorized.perform(
                        post("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000010")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {"applicationTargetId":"%s","nativePrincipalKey":"x","identityId":"%s"}
                                        """.formatted(
                                                target,
                                                actorIdentity.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("validation_failed"));

        authorized.perform(
                        post("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000011")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {"applicationTargetId":"%s","nativePrincipalKey":"x","lifecycleState":"DISABLED"}
                                        """.formatted(target)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("validation_failed"));
    }

    @Test
    void correlationIsOneWayRevisionGuardedAndTenantSafe()
            throws Exception {
        UUID target = target(
                tenant, "crm", "prod");
        var principal = principals.create(
                tenant,
                target,
                "crm-user-1",
                null,
                NOW,
                ids.nextId(),
                null);
        Identity first = identity(
                tenant, "First");
        Identity second = identity(
                tenant, "Second");

        authorized.perform(
                        post("/api/v1/principals/{principalId}:correlate",
                                principal.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000012")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {"identityId":"%s"}
                                        """.formatted(first.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision")
                        .value(2));

        authorized.perform(
                        post("/api/v1/principals/{principalId}:correlate",
                                principal.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000013")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {"identityId":"%s"}
                                        """.formatted(second.id())))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code")
                        .value("stale_revision"));

        Integer staleAudit = jdbc.queryForObject("""
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND actor_id = ?
                  AND action_type = 'principal:correlate'
                  AND resource_id = ?
                  AND outcome = 'FAILURE'
                """, Integer.class, tenant.tenantId(), actor.identityId(), principal.id());
        assertThat(staleAudit).isEqualTo(1);

        authorized.perform(
                        post("/api/v1/principals/{principalId}:correlate",
                                principal.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "If-Match",
                                        "\"rev-2\"")
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000014")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {"identityId":"%s"}
                                        """.formatted(second.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value(
                                "principal_already_correlated"));

        TenantContext other = new TenantContext(
                tenants.create(
                                "Other Principal Tenant",
                                NOW)
                        .id());
        Identity foreignIdentity = identity(
                other, "Foreign");
        UUID foreignTarget = target(
                other, "other", "prod");
        var foreignPrincipal = principals.create(
                other,
                foreignTarget,
                "foreign-user",
                null,
                NOW,
                ids.nextId(),
                null);

        authorized.perform(
                        get("/api/v1/principals/{principalId}",
                                foreignPrincipal.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor))
                .andExpect(status().isNotFound());

        var local = principals.create(
                tenant,
                target,
                "crm-user-2",
                null,
                NOW.plusSeconds(2),
                ids.nextId(),
                null);
        authorized.perform(
                        post("/api/v1/principals/{principalId}:correlate",
                                local.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000015")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {"identityId":"%s"}
                                        """.formatted(
                                                foreignIdentity.id())))
                .andExpect(status().isNotFound());
    }

    @Test
    void permissionsAndSignedCursorAreContextBound()
            throws Exception {
        UUID target = target(
                tenant, "payroll", "prod");
        principals.create(
                tenant,
                target,
                "payroll-1",
                null,
                NOW,
                ids.nextId(),
                null);
        principals.create(
                tenant,
                target,
                "payroll-2",
                null,
                NOW.plusSeconds(1),
                ids.nextId(),
                null);

        MockMvc readOnly = mockMvc(
                authorizationOnly(
                        AdministrativePermissions.PRINCIPAL_READ));
        String page = readOnly.perform(
                        get("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = mapper.readTree(page)
                .get("nextCursor")
                .asText();

        readOnly.perform(
                        post("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000016")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {"applicationTargetId":"%s","nativePrincipalKey":"denied"}
                                        """.formatted(target)))
                .andExpect(status().isForbidden());

        String tampered = cursor.substring(
                        0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");
        readOnly.perform(
                        get("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .param("cursor", tampered))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("validation_failed"));

        TenantContext other = new TenantContext(
                tenants.create(
                                "Cursor Other Tenant",
                                NOW)
                        .id());
        Identity otherActorIdentity = identity(
                other, "Other Admin");
        AuthenticatedAdministrativeActor otherActor =
                new AuthenticatedAdministrativeActor(
                        other,
                        otherActorIdentity.id());
        MockMvc otherRead = mockMvc(
                authorizationFor(
                        other,
                        otherActorIdentity,
                        AdministrativePermissions.PRINCIPAL_READ));

        otherRead.perform(
                        get("/api/v1/principals")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        otherActor)
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("validation_failed"));

        var uncorrelated = principals.create(
                tenant,
                target,
                "permission-correlation",
                null,
                NOW.plusSeconds(2),
                ids.nextId(),
                null);
        MockMvc registerOnly = mockMvc(
                authorizationOnly(
                        AdministrativePermissions.PRINCIPAL_REGISTER));
        registerOnly.perform(
                        post("/api/v1/principals/{principalId}:correlate",
                                uncorrelated.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "idempotency-00000017")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {"identityId":"%s"}
                                        """.formatted(
                                                actorIdentity.id())))
                .andExpect(status().isForbidden());

        Integer deniedRegister = jdbc.queryForObject("""
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND actor_id = ?
                  AND action_type = 'principal:register'
                  AND resource_id IS NULL
                  AND outcome = 'DENIED'
                """, Integer.class, tenant.tenantId(), actor.identityId());
        assertThat(deniedRegister).isEqualTo(1);

        Integer deniedCorrelate = jdbc.queryForObject("""
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND actor_id = ?
                  AND action_type = 'principal:correlate'
                  AND resource_id = ?
                  AND outcome = 'DENIED'
                """, Integer.class, tenant.tenantId(), actor.identityId(), uncorrelated.id());
        assertThat(deniedCorrelate).isEqualTo(1);
    }

    @Test
    void auditFailureDoesNotRewriteSuccessfulPrincipalOutcome()
            throws Exception {
        UUID target = target(tenant, "audit", "prod");
        SecurityAuditPort unavailableAudit = (requestedTenant, draft) -> {
            throw new IllegalStateException("audit unavailable");
        };
        MockMvc unavailable = mockMvc(authorization(true), unavailableAudit);

        String location = unavailable.perform(
                        post("/api/v1/principals")
                                .requestAttr(ACTOR_ATTRIBUTE, actor)
                                .header("Idempotency-Key", "principal-audit-unavailable")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"applicationTargetId":"%s","nativePrincipalKey":"audit-safe"}
                                        """.formatted(target)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        UUID principalId = UUID.fromString(
                location.substring(location.lastIndexOf('/') + 1));
        assertThat(principalRepository.findById(tenant, principalId)).isPresent();
    }

    private MockMvc mockMvc(
            AdministrativeAuthorizationService authorization) {
        return mockMvc(
                authorization,
                new AuditCommandService(
                        new JdbcAuditRecordRepository(jdbc),
                        transactions,
                        Clock.systemUTC()));
    }

    private MockMvc mockMvc(
            AdministrativeAuthorizationService authorization,
            SecurityAuditPort audit) {
        PrincipalApiMutationService mutations =
                new PrincipalApiMutationService(
                        authorization,
                        principals,
                        principalRepository,
                        idempotency,
                        transactions,
                        audit,
                        ids);
        PrincipalController controller =
                new PrincipalController(
                        queries,
                        mutations,
                        authorization,
                        ids,
                        cursors);
        return MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(
                        new IdentityApiErrorHandler(ids))
                .build();
    }

    private IdentityCursorCodec newCursorCodec() {
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair =
                    generator.generateKeyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial(
                            "principal-api-test",
                            "SHA256withRSA",
                            keyPair.getPublic());
            SigningKeyProvider provider =
                    new SigningKeyProvider() {
                        @Override
                        public SigningKeyMaterial
                                currentSigningKey() {
                            return material;
                        }

                        @Override
                        public Optional<SigningKeyMaterial>
                                verificationKey(
                                        String keyId) {
                            return material.keyId()
                                            .equals(keyId)
                                    ? Optional.of(material)
                                    : Optional.empty();
                        }

                        @Override
                        public byte[] sign(
                                byte[] payload) {
                            try {
                                Signature signature =
                                        Signature.getInstance(
                                                material.signingAlgorithm());
                                signature.initSign(
                                        keyPair.getPrivate());
                                signature.update(payload);
                                return signature.sign();
                            } catch (Exception exception) {
                                throw new IllegalStateException(
                                        exception);
                            }
                        }
                    };
            return new IdentityCursorCodec(
                    provider,
                    Duration.ofMinutes(15),
                    Clock.systemUTC());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AdministrativeAuthorizationService authorization(
            boolean allow) {
        return authorizationFor(
                tenant,
                actorIdentity,
                null,
                allow);
    }

    private AdministrativeAuthorizationService authorizationOnly(
            AdministrativePermission permission) {
        return authorizationFor(
                tenant,
                actorIdentity,
                permission,
                true);
    }

    private AdministrativeAuthorizationService authorizationFor(
            TenantContext authorizedTenant,
            Identity authorizedActor,
            AdministrativePermission permission) {
        return authorizationFor(
                authorizedTenant,
                authorizedActor,
                permission,
                true);
    }

    private AdministrativeAuthorizationService authorizationFor(
            TenantContext authorizedTenant,
            Identity authorizedActor,
            AdministrativePermission permission,
            boolean allow) {
        Instant grantTime =
                Instant.parse(
                        "2026-01-01T00:00:00Z");
        AdministrativeGrant grant =
                new AdministrativeGrant(
                        ids.nextId(),
                        authorizedActor.id(),
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
                (requestedTenant,
                        actorIdentityId,
                        requestedPermission) ->
                        allow
                                        && requestedTenant.equals(
                                                authorizedTenant)
                                        && actorIdentityId.equals(
                                                authorizedActor.id())
                                        && (permission == null
                                                || requestedPermission
                                                        .equals(
                                                                permission))
                                ? List.of(grant)
                                : List.of(),
                (requestedTenant,
                        actorIdentityId) ->
                        requestedTenant.equals(
                                        authorizedTenant)
                                && actorIdentityId.equals(
                                        authorizedActor.id())
                                && identityRepository
                                        .findById(
                                                requestedTenant,
                                                actorIdentityId)
                                        .map(identity ->
                                                identity.lifecycleState()
                                                        == IdentityLifecycleState.ACTIVE)
                                        .orElse(false));
    }

    private Identity identity(
            TenantContext identityTenant,
            String name) {
        return identities.create(
                identityTenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                name,
                NOW,
                ids.nextId(),
                null);
    }

    private UUID target(
            TenantContext targetTenant,
            String applicationCode,
            String targetCode) {
        UUID applicationId = ids.nextId();
        UUID targetId = ids.nextId();
        jdbc.update("""
                INSERT INTO catalog.application (
                    id, tenant_id, code, name,
                    lifecycle_state, revision,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
                """,
                applicationId,
                targetTenant.tenantId(),
                applicationCode,
                applicationCode,
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO catalog.application_target (
                    id, tenant_id, application_id,
                    code, lifecycle_state, revision,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
                """,
                targetId,
                targetTenant.tenantId(),
                applicationId,
                targetCode,
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        return targetId;
    }
}
