package io.wyrmgate.iam.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.DesiredProvisioningStateQueryService;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPresence;
import io.wyrmgate.iam.access.persistence.JdbcDesiredPrincipalFactSink;
import io.wyrmgate.iam.access.persistence.JdbcDesiredStateProjectionRepository;
import io.wyrmgate.iam.identity.application.PrincipalProvisioningProfileQuery;
import io.wyrmgate.iam.identity.application.PrincipalTechnicalReferenceQuery;
import io.wyrmgate.iam.integration.persistence.JdbcPrincipalProvisioningRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class PrincipalProvisioningPlanningIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-25T11:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcDesiredStateProjectionRepository desired;
    private static JdbcDesiredPrincipalFactSink facts;
    private static JdbcPrincipalProvisioningRepository provisioning;
    private static TransactionExecutor transactions;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("25");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        outbox = new JdbcOutboxRepository(jdbc);
        desired = new JdbcDesiredStateProjectionRepository(jdbc, ids);
        facts = new JdbcDesiredPrincipalFactSink(outbox, ids);
        provisioning = new JdbcPrincipalProvisioningRepository(
                jdbc, new ObjectMapper().findAndRegisterModules(), ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clear() {
        jdbc.execute("""
                TRUNCATE TABLE
                    integration.provisioning_attempt,
                    integration.provisioning_task_dependency,
                    integration.provisioning_task,
                    integration.provisioning_job,
                    integration.observed_principal,
                    integration.reconciliation_principal_staging,
                    integration.reconciliation_observation_batch,
                    integration.reconciliation_run,
                    integration.connector_binding,
                    integration.connector_instance,
                    access.desired_grant_state,
                    access.desired_principal_state,
                    platform.connector_work_lease,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
    }

    @Test
    void presentPrincipalPlansCreateWithConnectorOwnedTechnicalNameAndReplayIsIdempotent() {
        TenantContext tenant = tenant("create");
        UUID identityId = ids.nextId();
        UUID targetId = ids.nextId();
        principalBinding(tenant, targetId, "iam-{identityId}");

        var state = desired.reconcilePrincipal(
                tenant, identityId, targetId, DesiredPresence.PRESENT, NOW);
        facts.changed(tenant, state);

        var processor = processor(
                noPrincipals(),
                PrincipalProvisioningProfileQuery.Result.available(
                        identityId, "Ada Lovelace"));

        assertThat(processor.processAvailable()).isEqualTo(1);
        assertThat(jobCount(tenant)).isEqualTo(1);
        assertThat(taskCount(tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT operation_type FROM integration.provisioning_task
                WHERE tenant_id = ?
                """, String.class, tenant.tenantId()))
                .isEqualTo("UPSERT_PRINCIPAL");
        String payload = jdbc.queryForObject("""
                SELECT payload::text FROM integration.provisioning_task
                WHERE tenant_id = ?
                """, String.class, tenant.tenantId());
        assertThat(payload)
                .contains("iam-" + identityId)
                .contains("Ada Lovelace")
                .contains(identityId.toString())
                .doesNotContain("secret");

        facts.changed(tenant, state);
        assertThat(processor.processAvailable()).isEqualTo(1);
        assertThat(jobCount(tenant)).isEqualTo(1);
        assertThat(taskCount(tenant)).isEqualTo(1);
    }

    @Test
    void ambiguousCreateRouteFailsClosedWithoutProviderWork() {
        TenantContext tenant = tenant("ambiguous");
        UUID identityId = ids.nextId();
        UUID targetId = ids.nextId();
        principalBinding(tenant, targetId, "a-{identityId}");
        principalBinding(tenant, targetId, "b-{identityId}");

        var state = desired.reconcilePrincipal(
                tenant, identityId, targetId, DesiredPresence.PRESENT, NOW);
        facts.changed(tenant, state);

        assertThat(processor(
                noPrincipals(),
                PrincipalProvisioningProfileQuery.Result.available(
                        identityId, "Ada"))
                .processAvailable()).isZero();
        assertThat(jobCount(tenant)).isZero();
        assertThat(taskCount(tenant)).isZero();
    }

    private PrincipalProvisioningPlanningProcessor processor(
            PrincipalTechnicalReferenceQuery principals,
            PrincipalProvisioningProfileQuery.Result profile) {
        PrincipalProvisioningProfileQuery profiles =
                (tenant, identityId) -> profile;
        var planner = new PrincipalProvisioningPlannerService(
                new DesiredProvisioningStateQueryService(desired),
                principals,
                profiles,
                provisioning);
        return new PrincipalProvisioningPlanningProcessor(
                outbox,
                planner,
                transactions,
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
    }

    private PrincipalTechnicalReferenceQuery noPrincipals() {
        return new PrincipalTechnicalReferenceQuery() {
            @Override
            public Result resolve(TenantContext tenant, UUID principalId) {
                return Result.unavailable();
            }

            @Override
            public List<Result> activeForIdentityTarget(
                    TenantContext tenant,
                    UUID identityId,
                    UUID applicationTargetId) {
                return List.of();
            }

            @Override
            public List<Result> forIdentityTarget(
                    TenantContext tenant,
                    UUID identityId,
                    UUID applicationTargetId) {
                return List.of();
            }
        };
    }

    private UUID principalBinding(
            TenantContext tenant,
            UUID targetId,
            String template) {
        UUID connectorId = ids.nextId();
        UUID bindingId = ids.nextId();
        String config = "{\"principalUserNameTemplate\":\""
                + template + "\"}";
        jdbc.update("""
                INSERT INTO integration.connector_instance (
                    id, tenant_id, connector_type,
                    runtime_id, runtime_version,
                    configuration_version, configuration_json,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (
                    ?, ?, 'SCIM_2',
                    'scim-2', '1.0',
                    1, ?::jsonb,
                    'ACTIVE', 1, ?, ?)
                """,
                connectorId, tenant.tenantId(), config,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.connector_binding (
                    id, tenant_id, connector_instance_id,
                    target_kind, target_id,
                    contract_id, contract_version,
                    supports_complete_principal_discovery,
                    supports_complete_entitlement_discovery,
                    supports_complete_grant_discovery,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (
                    ?, ?, ?,
                    'APPLICATION_TARGET', ?,
                    'scim-2.principal', 1,
                    true, false, false,
                    'ACTIVE', 1, ?, ?)
                """,
                bindingId, tenant.tenantId(), connectorId, targetId,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return bindingId;
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, NOW).id());
    }

    private int jobCount(TenantContext tenant) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM integration.provisioning_job
                WHERE tenant_id = ?
                """, Integer.class, tenant.tenantId());
    }

    private int taskCount(TenantContext tenant) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM integration.provisioning_task
                WHERE tenant_id = ?
                """, Integer.class, tenant.tenantId());
    }
}
