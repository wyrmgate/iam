package io.wyrmgate.iam.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.administration.persistence.JdbcAdministrativeAuthorizationRepository;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogRepository;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.persistence.IdentityGovernedActorStatusQuery;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationCommandService;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationRepository;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingService;
import io.wyrmgate.iam.integration.domain.WorkerCapability;
import io.wyrmgate.iam.integration.domain.WorkerExternalSubject;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationAdministrationFactSink;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationAdministrationRepository;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationObservedAccessFactSink;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationRuntimeRepository;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationObservationRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.IdempotencyConflictException;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

class IntegrationAdminApiPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");
    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcIdentityRepository identities;
    private static IdentityCommandService identityCommands;
    private static AdministrativeAuthorizationService authorization;
    private static JdbcIntegrationAdministrationRepository integration;
    private static JdbcIntegrationRuntimeRepository runtime;
    private static IntegrationAdminApiMutationService mutations;
    private static TransactionExecutor transactions;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdempotencyRepository idempotency;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void setup() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        outbox = new JdbcOutboxRepository(jdbc);
        idempotency = new JdbcIdempotencyRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        identities = new JdbcIdentityRepository(jdbc);
        identityCommands = new IdentityCommandService(
                identities, new JdbcIdentityFactSink(outbox, ids), ids, transactions);
        authorization = new AdministrativeAuthorizationService(
                new JdbcAdministrativeAuthorizationRepository(jdbc),
                new IdentityGovernedActorStatusQuery(identities));
        integration = new JdbcIntegrationAdministrationRepository(jdbc, JSON);
        var observedAccessFacts = new JdbcIntegrationObservedAccessFactSink(outbox, ids);
        runtime = new JdbcIntegrationRuntimeRepository(jdbc, JSON, ids, observedAccessFacts);
        var factSink = new JdbcIntegrationAdministrationFactSink(outbox, ids);
        var commands = new IntegrationAdministrationCommandService(
                integration, factSink, ids, transactions);
        var observationMappings = new JdbcIntegrationObservationRepository(jdbc);
        var mappingCommands = new IntegrationEntitlementMappingService(
                integration,
                observationMappings,
                new CatalogQueryService(new JdbcCatalogRepository(jdbc)),
                factSink,
                observedAccessFacts,
                ids,
                transactions);
        mutations = new IntegrationAdminApiMutationService(
                authorization, commands, integration,
                mappingCommands, observationMappings,
                idempotency, transactions);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("18");
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clear() {
        jdbc.execute("""
                TRUNCATE TABLE
                    integration.connector_worker_session_contract,
                    integration.connector_worker_session_capability,
                    integration.connector_worker_session,
                    integration.connector_worker_runtime_permission,
                    integration.connector_worker_binding_scope,
                    integration.connector_worker_registration,
                    integration.connector_binding,
                    integration.connector_instance,
                    administration.administrative_grant,
                    administration.administrative_role_permission,
                    administration.administrative_role,
                    administration.administrative_permission,
                    platform.idempotency_record,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
    }

    @Test
    void defaultDenyExplicitGrantIdempotencyAndSecretBoundaries() {
        Instant now = Instant.parse("2026-09-21T08:00:00Z");
        TenantContext tenant = tenant("admin");
        var actor = actor(tenant, now);
        RequestFingerprint fingerprint = fp("connector-create-a");

        assertThatThrownBy(() -> mutations.createConnector(
                actor, "SCIM", "runtime.scim", "1.0", 1,
                Map.of("baseUrl", "https://provider.example"), "vault://connector/1",
                "connector-create-001", fingerprint, now, ids.nextId()))
                .isInstanceOf(IntegrationAdminApiException.class)
                .satisfies(e -> assertThat(((IntegrationAdminApiException)e).status().value()).isEqualTo(403));

        grant(tenant, actor.identityId(), AdministrativePermissions.CONNECTOR_CREATE, now);
        grant(tenant, actor.identityId(), AdministrativePermissions.CONNECTOR_READ, now);

        var created = mutations.createConnector(
                actor, "SCIM", "runtime.scim", "1.0", 1,
                Map.of("baseUrl", "https://provider.example"), "vault://connector/1",
                "connector-create-001", fingerprint, now, ids.nextId());

        assertThat(created.secretConfigured()).isTrue();
        assertThat(created.configuration()).containsEntry("baseUrl", "https://provider.example");
        assertThat(integration.findConnector(tenant, created.id()).orElseThrow().secretConfigured()).isTrue();

        var replay = mutations.createConnector(
                actor, "SCIM", "runtime.scim", "1.0", 1,
                Map.of("baseUrl", "https://provider.example"), "vault://connector/1",
                "connector-create-001", fingerprint, now.plusSeconds(1), ids.nextId());
        assertThat(replay.id()).isEqualTo(created.id());

        assertThatThrownBy(() -> mutations.createConnector(
                actor, "SCIM", "runtime.scim", "1.0", 2,
                Map.of("baseUrl", "https://different.example"), "vault://connector/2",
                "connector-create-001", fp("different"), now.plusSeconds(2), ids.nextId()))
                .isInstanceOf(IdempotencyConflictException.class);

        String factPayload = jdbc.queryForObject("""
                SELECT payload::text FROM platform.outbox_event
                WHERE tenant_id = ? AND event_type = 'integration.connector-created'
                """, String.class, tenant.tenantId());
        assertThat(factPayload)
                .isEqualTo("{}")
                .doesNotContain("vault://")
                .doesNotContain("provider.example");

        assertThatThrownBy(() -> mutations.createConnector(
                actor, "SCIM", "runtime.scim", "1.0", 1,
                Map.of("client_secret", "forbidden"), null,
                "connector-create-002", fp("secret"), now.plusSeconds(3), ids.nextId()))
                .isInstanceOf(IntegrationAdministrationException.class)
                .hasMessageContaining("forbidden secret-shaped");

        assertThat(AdministrativePermissions.INITIAL_TENANT_ADMIN)
                .doesNotContain(
                        AdministrativePermissions.CONNECTOR_CREATE,
                        AdministrativePermissions.CONNECTOR_WORKER_CREATE);
    }

    @Test
    void staleRevisionAndCrossTenantBindingFailClosed() {
        Instant now = Instant.parse("2026-09-21T08:10:00Z");
        TenantContext tenant = tenant("first");
        TenantContext other = tenant("other");
        var actor = actor(tenant, now);
        grant(tenant, actor.identityId(), AdministrativePermissions.CONNECTOR_CREATE, now);
        grant(tenant, actor.identityId(), AdministrativePermissions.CONNECTOR_UPDATE, now);
        grant(tenant, actor.identityId(), AdministrativePermissions.CONNECTOR_BINDING_CREATE, now);

        var connector = mutations.createConnector(
                actor, "SCIM", "runtime.scim", "1.0", 1, Map.of(), null,
                "connector-create-003", fp("create3"), now, ids.nextId());

        var updated = mutations.updateConnector(
                actor, connector.id(), "runtime.scim", "1.1", 2, Map.of(), null, 1,
                "connector-update-001", fp("update1"), now.plusSeconds(1), ids.nextId());
        assertThat(updated.revision()).isEqualTo(2);

        assertThatThrownBy(() -> mutations.updateConnector(
                actor, connector.id(), "runtime.scim", "1.2", 3, Map.of(), null, 1,
                "connector-update-002", fp("update2"), now.plusSeconds(2), ids.nextId()))
                .isInstanceOf(StaleWriteException.class);

        UUID otherConnector = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.connector_instance (
                    id, tenant_id, connector_type, runtime_id, runtime_version,
                    configuration_version, configuration_json, lifecycle_state,
                    revision, created_at, updated_at)
                VALUES (?, ?, 'SCIM', 'runtime.scim', '1.0', 1, '{}'::jsonb, 'ACTIVE', 1, ?, ?)
                """, otherConnector, other.tenantId(), Timestamp.from(now), Timestamp.from(now));

        assertThatThrownBy(() -> mutations.createBinding(
                actor, otherConnector, "APPLICATION_TARGET", ids.nextId(),
                "scim.principal", 1, true, false, false,
                "binding-create-001", fp("binding-cross"), now.plusSeconds(3), ids.nextId()))
                .isInstanceOf(IntegrationAdministrationException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void workerScopeCompatibilityAndDisableImmediatelyBlockResolution() {
        Instant now = Instant.parse("2026-09-21T08:20:00Z");
        TenantContext tenant = tenant("worker");
        var actor = actor(tenant, now);
        for (AdministrativePermission permission : List.of(
                AdministrativePermissions.CONNECTOR_CREATE,
                AdministrativePermissions.CONNECTOR_BINDING_CREATE,
                AdministrativePermissions.CONNECTOR_WORKER_CREATE,
                AdministrativePermissions.CONNECTOR_WORKER_UPDATE,
                AdministrativePermissions.CONNECTOR_WORKER_DISABLE)) {
            grant(tenant, actor.identityId(), permission, now);
        }

        var connector = mutations.createConnector(
                actor, "SCIM", "runtime.scim", "1.0", 1, Map.of(), null,
                "connector-create-004", fp("create4"), now, ids.nextId());
        var binding = mutations.createBinding(
                actor, connector.id(), "APPLICATION_TARGET", ids.nextId(),
                "scim.principal", 1, true, false, false,
                "binding-create-002", fp("binding2"), now.plusSeconds(1), ids.nextId());

        WorkerExternalSubject subject = new WorkerExternalSubject(
                "https://worker-issuer.example", "worker-a");
        var permission = new IntegrationAdministrationRepository.WorkerPermissionSpec(
                "runtime.scim", "1.0", WorkerCapability.PROVISION, "scim.principal", 1);
        var worker = mutations.createWorker(
                actor, subject, 1, 1, List.of(binding.id()), List.of(permission),
                "worker-create-001", fp("worker1"), now.plusSeconds(2), ids.nextId());

        assertThat(runtime.findEnabledByExternalSubject(subject)).isPresent();

        var incompatible = new IntegrationAdministrationRepository.WorkerPermissionSpec(
                "runtime.other", "9", WorkerCapability.PROVISION, "other.contract", 1);
        assertThatThrownBy(() -> mutations.updateWorker(
                actor, worker.id(), 1, 1, List.of(binding.id()), List.of(incompatible), 1,
                "worker-update-001", fp("worker-bad"), now.plusSeconds(3), ids.nextId()))
                .isInstanceOf(IntegrationAdministrationException.class)
                .hasMessageContaining("not compatible");

        var disabled = mutations.disableWorker(
                actor, worker.id(), 1,
                "worker-disable-001", fp("worker-disable"), now.plusSeconds(4), ids.nextId());
        assertThat(disabled.state()).isEqualTo("DISABLED");
        assertThat(runtime.findEnabledByExternalSubject(subject)).isEmpty();

        String workerFact = jdbc.queryForObject("""
                SELECT payload::text FROM platform.outbox_event
                WHERE tenant_id = ? AND event_type = 'integration.connector-worker-created'
                """, String.class, tenant.tenantId());
        assertThat(workerFact).isEqualTo("{}")
                .doesNotContain(subject.issuer())
                .doesNotContain(subject.subject());
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, Instant.now()).id());
    }

    private AuthenticatedAdministrativeActor actor(TenantContext tenant, Instant now) {
        var identity = identityCommands.create(
                tenant, IdentityType.PERSON, new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE, "Admin", now, ids.nextId(), null);
        return new AuthenticatedAdministrativeActor(tenant, identity.id());
    }

    private void grant(
            TenantContext tenant, UUID actorId, AdministrativePermission permission, Instant now) {
        UUID permissionId=ids.nextId();
        UUID roleId=ids.nextId();
        jdbc.update("""
                INSERT INTO administration.administrative_permission
                    (id, tenant_id, resource_type, action, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, permissionId, tenant.tenantId(), permission.resourceType(),
                permission.action(), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO administration.administrative_role
                    (id, tenant_id, code, name, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                """, roleId, tenant.tenantId(), "role-"+roleId, "Integration Admin",
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO administration.administrative_role_permission
                    (tenant_id, role_id, permission_id, created_at)
                VALUES (?, ?, ?, ?)
                """, tenant.tenantId(), roleId, permissionId, Timestamp.from(now));
        jdbc.update("""
                INSERT INTO administration.administrative_grant (
                    id, tenant_id, actor_identity_id, role_id, scope_type,
                    state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'GLOBAL', 'ACTIVE', 1, ?, ?)
                """, ids.nextId(), tenant.tenantId(), actorId, roleId,
                Timestamp.from(now), Timestamp.from(now));
    }

    private static RequestFingerprint fp(String value) {
        return RequestFingerprint.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
