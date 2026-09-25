package io.wyrmgate.iam.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.DesiredProvisioningStateQueryService;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredGrantState;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository.DesiredPresence;
import io.wyrmgate.iam.access.persistence.JdbcDesiredGrantFactSink;
import io.wyrmgate.iam.access.persistence.JdbcDesiredStateProjectionRepository;
import io.wyrmgate.iam.identity.application.PrincipalTechnicalReferenceQuery;
import io.wyrmgate.iam.integration.persistence.JdbcGrantProvisioningRepository;
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

class GrantProvisioningPlanningIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-25T09:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcDesiredStateProjectionRepository desired;
    private static JdbcDesiredGrantFactSink facts;
    private static JdbcGrantProvisioningRepository provisioning;
    private static TransactionExecutor transactions;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("22");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        outbox = new JdbcOutboxRepository(jdbc);
        desired = new JdbcDesiredStateProjectionRepository(jdbc, ids);
        facts = new JdbcDesiredGrantFactSink(outbox, ids);
        provisioning = new JdbcGrantProvisioningRepository(
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
                    integration.entitlement_observation_mapping,
                    integration.observed_grant,
                    integration.observed_entitlement,
                    integration.reconciliation_entitlement_staging,
                    integration.reconciliation_grant_staging,
                    integration.reconciliation_principal_staging,
                    integration.reconciliation_observation_batch,
                    integration.reconciliation_run,
                    integration.connector_worker_session_contract,
                    integration.connector_worker_session_capability,
                    integration.connector_worker_session,
                    integration.connector_worker_runtime_permission,
                    integration.connector_worker_binding_scope,
                    integration.connector_worker_registration,
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
    void presentGrantPlansOneAddAndReplayOrStaleFactDoesNotDuplicate() {
        TenantContext tenant = tenant("add");
        UUID targetId = ids.nextId();
        UUID entitlementId = ids.nextId();
        UUID identityId = ids.nextId();
        UUID principalId = ids.nextId();
        UUID bindingId = mappedGroupBinding(
                tenant, targetId, entitlementId, "group-1");

        DesiredGrantState grant = desired.reconcileGrant(
                tenant,
                identityId,
                targetId,
                entitlementId,
                "ANY",
                principalId,
                DesiredPresence.PRESENT,
                NOW);
        facts.changed(tenant, grant);

        assertThat(provisioning.addTargets(
                tenant, targetId, entitlementId, "provider-user-1"))
                .hasSize(1);

        var principalQuery = activePrincipal(
                principalId, identityId, targetId, "provider-user-1");
        var directPlanner = planner(principalQuery);
        assertThat(directPlanner.plan(
                tenant,
                grant.id(),
                grant.desiredRevision(),
                ids.nextId(),
                null,
                NOW.plusSeconds(5)))
                .isEqualTo(GrantProvisioningPlannerService.PlanResult.PLANNED);

        var processor = processor(principalQuery);
        int processed = processor.processAvailable();
        assertThat(processed)
                .withFailMessage(
                        "planner outbox error: %s",
                        latestOutboxError(tenant))
                .isEqualTo(1);

        assertThat(jobCount(tenant)).isEqualTo(1);
        assertThat(taskCount(tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT operation_type
                FROM integration.provisioning_task
                WHERE tenant_id = ?
                """, String.class, tenant.tenantId()))
                .isEqualTo("ADD_GRANT");
        assertThat(jdbc.queryForObject("""
                SELECT desired_revision
                FROM integration.provisioning_task
                WHERE tenant_id = ?
                """, Long.class, tenant.tenantId()))
                .isEqualTo(grant.desiredRevision());

        String payload = jdbc.queryForObject("""
                SELECT payload::text
                FROM integration.provisioning_task
                WHERE tenant_id = ?
                """, String.class, tenant.tenantId());
        assertThat(payload)
                .contains("providerEntitlementId")
                .contains("group-1")
                .contains("providerPrincipalId")
                .contains("provider-user-1")
                .doesNotContain("secret");

        facts.changed(tenant, grant);
        assertThat(processor.processAvailable()).isEqualTo(1);
        assertThat(jobCount(tenant)).isEqualTo(1);
        assertThat(taskCount(tenant)).isEqualTo(1);

        DesiredGrantState revisionTwo = new DesiredGrantState(
                grant.id(),
                grant.identityId(),
                grant.applicationTargetId(),
                grant.entitlementId(),
                grant.principalConstraintKey(),
                grant.principalId(),
                DesiredPresence.PRESENT,
                grant.desiredRevision() + 1,
                grant.sourceGeneration() + 1,
                NOW.plusSeconds(1));
        desired.replaceGrant(tenant, revisionTwo);
        facts.changed(tenant, grant);

        assertThat(processor.processAvailable()).isEqualTo(1);
        assertThat(jobCount(tenant)).isEqualTo(1);
        assertThat(taskCount(tenant)).isEqualTo(1);
        assertThat(bindingId).isNotNull();
    }

    @Test
    void unresolvedOrAmbiguousPrivilegeIncreaseRetriesWithoutCreatingWork() {
        TenantContext tenant = tenant("closed");
        UUID targetId = ids.nextId();
        UUID entitlementId = ids.nextId();
        UUID identityId = ids.nextId();
        UUID principalId = ids.nextId();
        mappedGroupBinding(tenant, targetId, entitlementId, "group-1");

        DesiredGrantState unresolved = desired.reconcileGrant(
                tenant,
                identityId,
                targetId,
                entitlementId,
                "ANY",
                null,
                DesiredPresence.PRESENT,
                NOW);
        facts.changed(tenant, unresolved);
        assertThat(processor(unavailablePrincipal()).processAvailable()).isZero();
        assertThat(jobCount(tenant)).isZero();

        clearOutbox();
        DesiredGrantState resolved = desired.reconcileGrant(
                tenant,
                identityId,
                targetId,
                entitlementId,
                "ANY",
                principalId,
                DesiredPresence.PRESENT,
                NOW.plusSeconds(1));
        mappedEntitlement(
                tenant,
                activeBindingId(tenant, targetId),
                entitlementId,
                "group-2");
        facts.changed(tenant, resolved);

        assertThat(processor(activePrincipal(
                principalId, identityId, targetId, "provider-user-1"))
                .processAvailable()).isZero();
        assertThat(jobCount(tenant)).isZero();
    }

    @Test
    void absentGrantPlansRemoveFromPriorSuccessfulAddAndNoKnownGrantIsNoop() {
        TenantContext tenant = tenant("remove");
        UUID targetId = ids.nextId();
        UUID entitlementId = ids.nextId();
        UUID identityId = ids.nextId();
        UUID principalId = ids.nextId();
        mappedGroupBinding(tenant, targetId, entitlementId, "group-1");

        DesiredGrantState present = desired.reconcileGrant(
                tenant,
                identityId,
                targetId,
                entitlementId,
                "ANY",
                principalId,
                DesiredPresence.PRESENT,
                NOW);
        facts.changed(tenant, present);
        var principalQuery = activePrincipal(
                principalId, identityId, targetId, "provider-user-1");
        var directPlanner = planner(principalQuery);
        assertThat(directPlanner.plan(
                tenant,
                present.id(),
                present.desiredRevision(),
                ids.nextId(),
                null,
                NOW.plusSeconds(5)))
                .isEqualTo(GrantProvisioningPlannerService.PlanResult.PLANNED);

        var processor = processor(principalQuery);
        int initialProcessed = processor.processAvailable();
        assertThat(initialProcessed)
                .withFailMessage(
                        "planner outbox error: %s",
                        latestOutboxError(tenant))
                .isEqualTo(1);

        jdbc.update("""
                UPDATE integration.provisioning_task
                SET state = 'SUCCEEDED', revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND operation_type = 'ADD_GRANT'
                """, Timestamp.from(NOW.plusSeconds(1)), tenant.tenantId());

        DesiredGrantState absent = desired.reconcileGrant(
                tenant,
                identityId,
                targetId,
                entitlementId,
                "ANY",
                null,
                DesiredPresence.ABSENT,
                NOW.plusSeconds(2));
        facts.changed(tenant, absent);
        assertThat(processor.processAvailable()).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM integration.provisioning_task
                WHERE tenant_id = ? AND operation_type = 'REMOVE_GRANT'
                """, Integer.class, tenant.tenantId()))
                .isEqualTo(1);

        UUID otherEntitlement = ids.nextId();
        DesiredGrantState alreadyAbsent = desired.reconcileGrant(
                tenant,
                identityId,
                targetId,
                otherEntitlement,
                "ANY",
                null,
                DesiredPresence.ABSENT,
                NOW.plusSeconds(3));
        facts.changed(tenant, alreadyAbsent);
        assertThat(processor.processAvailable()).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM integration.provisioning_job
                WHERE tenant_id = ?
                """, Integer.class, tenant.tenantId()))
                .isEqualTo(2);
    }

    private GrantProvisioningPlannerService planner(
            PrincipalTechnicalReferenceQuery principals) {
        return new GrantProvisioningPlannerService(
                new DesiredProvisioningStateQueryService(desired),
                principals,
                provisioning);
    }

    private GrantProvisioningPlanningProcessor processor(
            PrincipalTechnicalReferenceQuery principals) {
        return new GrantProvisioningPlanningProcessor(
                outbox,
                planner(principals),
                transactions,
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
    }

    private PrincipalTechnicalReferenceQuery activePrincipal(
            UUID principalId,
            UUID identityId,
            UUID targetId,
            String providerPrincipalId) {
        var active = PrincipalTechnicalReferenceQuery.Result.active(
                principalId, identityId, targetId, providerPrincipalId);
        return new PrincipalTechnicalReferenceQuery() {
            @Override
            public Result resolve(TenantContext tenant, UUID requestedPrincipalId) {
                return requestedPrincipalId.equals(principalId)
                        ? active
                        : Result.unavailable();
            }

            @Override
            public List<Result> activeForIdentityTarget(
                    TenantContext tenant,
                    UUID requestedIdentityId,
                    UUID requestedTargetId) {
                return requestedIdentityId.equals(identityId)
                                && requestedTargetId.equals(targetId)
                        ? List.of(active)
                        : List.of();
            }
        };
    }

    private PrincipalTechnicalReferenceQuery unavailablePrincipal() {
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
        };
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, NOW).id());
    }

    private UUID mappedGroupBinding(
            TenantContext tenant,
            UUID targetId,
            UUID entitlementId,
            String providerEntitlementId) {
        UUID connectorId = ids.nextId();
        UUID bindingId = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.connector_instance (
                    id, tenant_id, connector_type,
                    runtime_id, runtime_version,
                    configuration_version, configuration_json,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (
                    ?, ?, 'SCIM_2',
                    'scim-2', '1.0',
                    1, '{}'::jsonb,
                    'ACTIVE', 1, ?, ?)
                """,
                connectorId, tenant.tenantId(),
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
                    'scim-2.group', 1,
                    true, true, true,
                    'ACTIVE', 1, ?, ?)
                """,
                bindingId, tenant.tenantId(), connectorId, targetId,
                Timestamp.from(NOW), Timestamp.from(NOW));
        mappedEntitlement(
                tenant, bindingId, entitlementId, providerEntitlementId);
        return bindingId;
    }

    private void mappedEntitlement(
            TenantContext tenant,
            UUID bindingId,
            UUID entitlementId,
            String providerEntitlementId) {
        UUID runId = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.reconciliation_run (
                    id, tenant_id, connector_binding_id, operation_id,
                    scope_object_class, state,
                    reported_coverage, effective_completeness,
                    configuration_version,
                    runtime_id, runtime_version,
                    contract_id, contract_version,
                    checkpoint_start, checkpoint_end,
                    next_attempt_at, failure_code,
                    completion_lease_epoch, completion_fingerprint,
                    correlation_id, causation_id,
                    revision, started_at, completed_at,
                    created_at, updated_at)
                VALUES (
                    ?, ?, ?, ?,
                    'ENTITLEMENT', 'SUCCEEDED',
                    'COMPLETE', 'COMPLETE',
                    1,
                    'scim-2', '1.0',
                    'scim-2.group', 1,
                    NULL, NULL,
                    NULL, NULL,
                    NULL, NULL,
                    ?, NULL,
                    1, ?, ?,
                    ?, ?)
                """,
                runId, tenant.tenantId(), bindingId, ids.nextId(),
                ids.nextId(),
                Timestamp.from(NOW), Timestamp.from(NOW),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.observed_entitlement (
                    id, tenant_id, connector_binding_id,
                    provider_stable_id, provider_version,
                    observed_state, present,
                    last_observed_run_id, observed_at, absent_at)
                VALUES (?, ?, ?, ?, '"v1"', '{}'::jsonb, true, ?, ?, NULL)
                """,
                ids.nextId(), tenant.tenantId(), bindingId,
                providerEntitlementId, runId, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.entitlement_observation_mapping (
                    id, tenant_id, connector_binding_id,
                    provider_stable_id, entitlement_id,
                    lifecycle_state, revision,
                    created_at, updated_at, retired_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, NULL)
                """,
                ids.nextId(), tenant.tenantId(), bindingId,
                providerEntitlementId, entitlementId,
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private UUID activeBindingId(TenantContext tenant, UUID targetId) {
        return jdbc.queryForObject("""
                SELECT id
                FROM integration.connector_binding
                WHERE tenant_id = ? AND target_id = ?
                  AND lifecycle_state = 'ACTIVE'
                ORDER BY id
                LIMIT 1
                """, UUID.class, tenant.tenantId(), targetId);
    }

    private String latestOutboxError(TenantContext tenant) {
        return jdbc.query("""
                SELECT COALESCE(last_error_code, '<none>')
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND event_type = 'access.desired-grant-changed'
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """,
                (rs,row) -> rs.getString(1),
                tenant.tenantId())
                .stream()
                .findFirst()
                .orElse("<missing>");
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

    private void clearOutbox() {
        jdbc.execute("TRUNCATE TABLE platform.outbox_event CASCADE");
    }
}
