package io.wyrmgate.iam.governance.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogRepository;
import io.wyrmgate.iam.governance.application.GovernanceFindingRepository;
import io.wyrmgate.iam.governance.application.GovernanceObservationProcessingService;
import io.wyrmgate.iam.governance.application.GovernanceObservationReportingService;
import io.wyrmgate.iam.governance.application.ObservedAccessDriftEvaluationService;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.identity.application.PrincipalQueryService;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalRepository;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationFactSink;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingService;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationAdministrationRepository;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationObservationRepository;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationObservedAccessFactSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
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

class ObservedAccessDriftPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-21T10:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;
    private static IntegrationEntitlementMappingService mappingService;
    private static JdbcIntegrationObservationRepository observations;
    private static GovernanceFindingRepository findings;
    private static ObservedAccessDriftEvaluationService drift;
    private static GovernanceObservationProcessingService processor;
    private static IdentityCommandService identityCommands;
    private static PrincipalCommandService principalCommands;

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
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var catalogRepository = new JdbcCatalogRepository(jdbc);
        var catalog = new CatalogQueryService(catalogRepository);
        var administration = new JdbcIntegrationAdministrationRepository(
                jdbc, objectMapper);
        observations = new JdbcIntegrationObservationRepository(jdbc);
        var outbox = new JdbcOutboxRepository(jdbc);
        var observedAccessFacts = new JdbcIntegrationObservedAccessFactSink(outbox, ids);
        IntegrationAdministrationFactSink noFacts = new IntegrationAdministrationFactSink() {
            @Override
            public void connectorChanged(
                    TenantContext tenant, String factType, UUID connectorId, long revision,
                    Instant occurredAt, UUID correlationId) {}
            @Override
            public void bindingChanged(
                    TenantContext tenant, String factType, UUID bindingId, long revision,
                    Instant occurredAt, UUID correlationId) {}
            @Override
            public void workerChanged(
                    TenantContext tenant, String factType, UUID workerId, long revision,
                    Instant occurredAt, UUID correlationId) {}
            @Override
            public void mappingChanged(
                    TenantContext tenant, String factType, UUID mappingId, long revision,
                    Instant occurredAt, UUID correlationId) {}
        };
        mappingService = new IntegrationEntitlementMappingService(
                administration, observations, catalog, noFacts,
                observedAccessFacts, ids, transactions);

        var identityRepository = new JdbcIdentityRepository(jdbc);
        identityCommands = new IdentityCommandService(
                identityRepository,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);
        var principalRepository = new JdbcPrincipalRepository(jdbc);
        var principalQuery = new PrincipalQueryService(principalRepository);
        principalCommands = new PrincipalCommandService(
                principalRepository,
                identityRepository,
                catalog,
                new JdbcPrincipalFactSink(outbox, ids),
                ids,
                transactions);

        findings = new JdbcGovernanceFindingRepository(jdbc);
        var reporter = new GovernanceObservationReportingService(findings, ids, transactions);
        drift = new ObservedAccessDriftEvaluationService(
                observations, principalQuery, reporter);
        processor = new GovernanceObservationProcessingService(
                outbox, drift, observations, objectMapper);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clear() {
        jdbc.execute("""
                TRUNCATE TABLE
                    governance.finding,
                    identity.principal,
                    identity.person_profile,
                    identity.service_profile,
                    identity.workload_profile,
                    identity.identity,
                    integration.entitlement_observation_mapping,
                    integration.observed_grant,
                    integration.observed_entitlement,
                    integration.reconciliation_grant_staging,
                    integration.reconciliation_entitlement_staging,
                    integration.reconciliation_principal_staging,
                    integration.reconciliation_observation_batch,
                    integration.reconciliation_run,
                    integration.connector_binding,
                    integration.connector_instance,
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
    }

    @Test
    void mappingRequiresPresentObservationAndActiveEntitlementOnSameTarget() {
        TenantContext tenant = tenant("mapping");
        UUID applicationId = application(tenant, "app");
        UUID targetA = target(tenant, applicationId, "a");
        UUID targetB = target(tenant, applicationId, "b");
        UUID entitlementA = entitlement(tenant, applicationId, targetA, "finance");
        UUID entitlementB = entitlement(tenant, applicationId, targetB, "other");
        UUID binding = binding(tenant, targetA);
        UUID run = reconciliation(tenant, binding, "ENTITLEMENT");

        assertThatThrownBy(() -> mappingService.map(
                tenant, binding, "g-1", entitlementA, NOW, ids.nextId()))
                .isInstanceOf(IntegrationAdministrationException.class)
                .hasMessageContaining("observed entitlement");

        observedEntitlement(tenant, binding, run, "g-1");

        assertThatThrownBy(() -> mappingService.map(
                tenant, binding, "g-1", entitlementB, NOW, ids.nextId()))
                .isInstanceOf(IntegrationAdministrationException.class)
                .hasMessageContaining("ApplicationTarget");

        TenantContext otherTenant = tenant("mapping-other");
        assertThatThrownBy(() -> mappingService.map(
                otherTenant, binding, "g-1", entitlementA, NOW, ids.nextId()))
                .isInstanceOf(IntegrationAdministrationException.class)
                .hasMessageContaining("binding");

        var mapped = mappingService.map(
                tenant, binding, "g-1", entitlementA, NOW, ids.nextId());
        assertThat(mapped.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(mapped.entitlementId()).isEqualTo(entitlementA);

        assertThatThrownBy(() -> mappingService.map(
                tenant, binding, "g-1", entitlementA, NOW.plusSeconds(1), ids.nextId()))
                .isInstanceOf(IntegrationAdministrationException.class);

        var retired = mappingService.unmap(
                tenant, mapped.id(), mapped.revision(), NOW.plusSeconds(2), ids.nextId());
        assertThat(retired.lifecycleState()).isEqualTo("RETIRED");
        assertThat(retired.retiredAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void driftFindingsTransitionWhenProviderEntitlementMappingChanges() {
        TenantContext tenant = tenant("drift");
        UUID applicationId = application(tenant, "app");
        UUID target = target(tenant, applicationId, "prod");
        UUID entitlement = entitlement(tenant, applicationId, target, "finance");
        UUID binding = binding(tenant, target);
        UUID entitlementRun = reconciliation(tenant, binding, "ENTITLEMENT");
        UUID grantRun = reconciliation(tenant, binding, "GRANT");

        observedEntitlement(tenant, binding, entitlementRun, "g-1");
        observedEntitlement(tenant, binding, entitlementRun, "g-2");
        observedGrant(tenant, binding, grantRun, "grant-1", "u-1", "g-1");
        observedGrant(tenant, binding, grantRun, "grant-2", "u-2", "g-2");

        drift.evaluate(tenant, binding, NOW);
        assertThat(openFindingTypes(tenant, binding))
                .containsExactlyInAnyOrder(
                        "UNMAPPED_PROVIDER_ENTITLEMENT",
                        "UNMAPPED_PROVIDER_ENTITLEMENT",
                        "UNMAPPED_PROVIDER_GRANT_ENTITLEMENT",
                        "UNMAPPED_PROVIDER_GRANT_ENTITLEMENT");

        var mapped = mappingService.map(
                tenant, binding, "g-1", entitlement, NOW.plusSeconds(1), ids.nextId());
        drift.evaluate(tenant, binding, NOW.plusSeconds(2));

        assertThat(openFindingTypes(tenant, binding))
                .containsExactlyInAnyOrder(
                        "UNMAPPED_PROVIDER_ENTITLEMENT",
                        "UNMAPPED_PROVIDER_GRANT_ENTITLEMENT",
                        "UNRESOLVED_PROVIDER_GRANT_PRINCIPAL");
        assertThat(countFindings(
                tenant, binding, "UNRESOLVED_PROVIDER_GRANT_PRINCIPAL", "grant-1"))
                .isEqualTo(1);

        mappingService.unmap(
                tenant, mapped.id(), mapped.revision(), NOW.plusSeconds(3), ids.nextId());
        drift.evaluate(tenant, binding, NOW.plusSeconds(4));

        assertThat(openFindingTypes(tenant, binding))
                .containsExactlyInAnyOrder(
                        "UNMAPPED_PROVIDER_ENTITLEMENT",
                        "UNMAPPED_PROVIDER_ENTITLEMENT",
                        "UNMAPPED_PROVIDER_GRANT_ENTITLEMENT",
                        "UNMAPPED_PROVIDER_GRANT_ENTITLEMENT");
        assertThat(countOpenFindings(
                tenant, binding, "UNRESOLVED_PROVIDER_GRANT_PRINCIPAL", "grant-1"))
                .isZero();
    }

    @Test
    void mappingChangesDriveGovernanceThroughDurableObservedAccessFacts() {
        TenantContext tenant = tenant("durable-drift");
        UUID applicationId = application(tenant, "app");
        UUID target = target(tenant, applicationId, "prod");
        UUID entitlement = entitlement(tenant, applicationId, target, "finance");
        UUID binding = binding(tenant, target);
        UUID run = reconciliation(tenant, binding, "ENTITLEMENT");
        observedEntitlement(tenant, binding, run, "g-1");

        var mapped = mappingService.map(
                tenant, binding, "g-1", entitlement, NOW, ids.nextId());

        String payload = jdbc.queryForObject("""
                SELECT payload::text
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND event_type = 'integration.observed-access-input-changed'
                  AND aggregate_id = ?
                """, String.class, tenant.tenantId(), mapped.id());
        assertThat(payload)
                .contains(binding.toString())
                .doesNotContain("g-1")
                .doesNotContain(entitlement.toString());

        var mappedBatch = processor.processAvailable();
        assertThat(mappedBatch.claimed()).isEqualTo(1);
        assertThat(mappedBatch.processed()).isEqualTo(1);
        assertThat(countOpenFindings(
                tenant, binding, "UNMAPPED_PROVIDER_ENTITLEMENT", "g-1"))
                .isZero();

        var retired = mappingService.unmap(
                tenant, mapped.id(), mapped.revision(), NOW.plusSeconds(1), ids.nextId());
        var unmappedBatch = processor.processAvailable();
        assertThat(unmappedBatch.claimed()).isEqualTo(1);
        assertThat(unmappedBatch.processed()).isEqualTo(1);
        assertThat(countOpenFindings(
                tenant, binding, "UNMAPPED_PROVIDER_ENTITLEMENT", "g-1"))
                .isEqualTo(1);

        mappingService.map(
                tenant, binding, "g-1", entitlement, NOW.plusSeconds(2), ids.nextId());
        var remappedBatch = processor.processAvailable();
        assertThat(remappedBatch.claimed()).isEqualTo(1);
        assertThat(remappedBatch.processed()).isEqualTo(1);
        assertThat(countOpenFindings(
                tenant, binding, "UNMAPPED_PROVIDER_ENTITLEMENT", "g-1"))
                .isZero();
        assertThat(countFindings(
                tenant, binding, "UNMAPPED_PROVIDER_ENTITLEMENT", "g-1"))
                .isEqualTo(1);
        assertThat(retired.lifecycleState()).isEqualTo("RETIRED");
    }

    @Test
    void explicitPrincipalCorrelationResolvesUnresolvedGrantThroughDurableFact() {
        TenantContext tenant = tenant("principal-drift");
        UUID applicationId = application(tenant, "app");
        UUID target = target(tenant, applicationId, "prod");
        UUID entitlement = entitlement(tenant, applicationId, target, "finance");
        UUID binding = binding(tenant, target);
        UUID entitlementRun = reconciliation(tenant, binding, "ENTITLEMENT");
        UUID grantRun = reconciliation(tenant, binding, "GRANT");

        observedEntitlement(tenant, binding, entitlementRun, "g-1");
        observedGrant(tenant, binding, grantRun, "grant-1", "user-1", "g-1");
        mappingService.map(
                tenant, binding, "g-1", entitlement, NOW, ids.nextId());

        var initial = processor.processAvailable();
        assertThat(initial.claimed()).isEqualTo(1);
        assertThat(countOpenFindings(
                tenant, binding, "UNRESOLVED_PROVIDER_GRANT_PRINCIPAL", "grant-1"))
                .isEqualTo(1);

        Identity identity = identityCommands.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                "Resolved User",
                NOW.plusSeconds(1),
                ids.nextId(),
                null);
        var principal = principalCommands.create(
                tenant,
                target,
                "user-1",
                null,
                NOW.plusSeconds(2),
                ids.nextId(),
                null);

        assertThat(processor.processAvailable().claimed()).isZero();

        principalCommands.correlate(
                tenant,
                principal.id(),
                identity.id(),
                principal.revision(),
                NOW.plusSeconds(3),
                ids.nextId(),
                null);

        var correlated = processor.processAvailable();
        assertThat(correlated.claimed()).isEqualTo(1);
        assertThat(correlated.processed()).isEqualTo(1);
        assertThat(countOpenFindings(
                tenant, binding, "UNRESOLVED_PROVIDER_GRANT_PRINCIPAL", "grant-1"))
                .isZero();
        assertThat(countFindings(
                tenant, binding, "UNRESOLVED_PROVIDER_GRANT_PRINCIPAL", "grant-1"))
                .isEqualTo(1);

        Integer assignments = jdbc.queryForObject(
                "SELECT count(*) FROM access.desired_grant_state WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId());
        assertThat(assignments).isZero();
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, NOW).id());
    }

    private UUID application(TenantContext tenant, String code) {
        UUID id = ids.nextId();
        jdbc.update("""
                INSERT INTO catalog.application (
                    id, tenant_id, code, name, lifecycle_state,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
                """,
                id, tenant.tenantId(), code, code,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private UUID target(TenantContext tenant, UUID applicationId, String code) {
        UUID id = ids.nextId();
        jdbc.update("""
                INSERT INTO catalog.application_target (
                    id, tenant_id, application_id, code, lifecycle_state,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
                """,
                id, tenant.tenantId(), applicationId, code,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private UUID entitlement(
            TenantContext tenant, UUID applicationId, UUID targetId, String code) {
        UUID id = ids.nextId();
        jdbc.update("""
                INSERT INTO catalog.entitlement (
                    id, tenant_id, application_id, application_target_id,
                    code, native_key, entitlement_type, lifecycle_state,
                    revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'GROUP', 'ACTIVE', 1, ?, ?)
                """,
                id, tenant.tenantId(), applicationId, targetId,
                code, code, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private UUID binding(TenantContext tenant, UUID targetId) {
        UUID connectorId = ids.nextId();
        UUID bindingId = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.connector_instance (
                    id, tenant_id, connector_type, runtime_id, runtime_version,
                    configuration_version, configuration_json,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, 'TEST', 'runtime.test', '1.0',
                        1, '{}'::jsonb, 'ACTIVE', 1, ?, ?)
                """,
                connectorId, tenant.tenantId(), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO integration.connector_binding (
                    id, tenant_id, connector_instance_id, target_kind, target_id,
                    contract_id, contract_version,
                    supports_complete_principal_discovery,
                    supports_complete_entitlement_discovery,
                    supports_complete_grant_discovery,
                    lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, 'APPLICATION_TARGET', ?,
                        'test.group', 1, false, true, true,
                        'ACTIVE', 1, ?, ?)
                """,
                bindingId, tenant.tenantId(), connectorId, targetId,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return bindingId;
    }

    private UUID reconciliation(
            TenantContext tenant, UUID bindingId, String objectClass) {
        UUID id = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.reconciliation_run (
                    id, tenant_id, connector_binding_id, operation_id,
                    scope_object_class, state, reported_coverage,
                    effective_completeness, configuration_version,
                    runtime_id, runtime_version, contract_id, contract_version,
                    correlation_id, revision, started_at, completed_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', 'COMPLETE', 'COMPLETE',
                        1, 'runtime.test', '1.0', 'test.group', 1,
                        ?, 1, ?, ?, ?, ?)
                """,
                id, tenant.tenantId(), bindingId, ids.nextId(), objectClass,
                ids.nextId(), Timestamp.from(NOW), Timestamp.from(NOW),
                Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private void observedEntitlement(
            TenantContext tenant, UUID bindingId, UUID runId, String providerId) {
        jdbc.update("""
                INSERT INTO integration.observed_entitlement (
                    id, tenant_id, connector_binding_id, provider_stable_id,
                    provider_version, observed_state, present,
                    last_observed_run_id, observed_at, absent_at)
                VALUES (?, ?, ?, ?, 'v1', '{}'::jsonb, true, ?, ?, NULL)
                """,
                ids.nextId(), tenant.tenantId(), bindingId, providerId,
                runId, Timestamp.from(NOW));
    }

    private void observedGrant(
            TenantContext tenant,
            UUID bindingId,
            UUID runId,
            String grantId,
            String principalId,
            String entitlementId) {
        jdbc.update("""
                INSERT INTO integration.observed_grant (
                    id, tenant_id, connector_binding_id, provider_stable_id,
                    provider_version, principal_provider_id,
                    entitlement_provider_id, observed_state, present,
                    last_observed_run_id, observed_at, absent_at)
                VALUES (?, ?, ?, ?, 'v1', ?, ?, '{}'::jsonb, true, ?, ?, NULL)
                """,
                ids.nextId(), tenant.tenantId(), bindingId, grantId,
                principalId, entitlementId, runId, Timestamp.from(NOW));
    }

    private java.util.List<String> openFindingTypes(
            TenantContext tenant, UUID bindingId) {
        return jdbc.query("""
                SELECT finding_type
                FROM governance.finding
                WHERE tenant_id = ? AND connector_binding_id = ?
                  AND lifecycle_state = 'OPEN'
                ORDER BY finding_key
                """, (rs,row) -> rs.getString(1), tenant.tenantId(), bindingId);
    }

    private int countFindings(
            TenantContext tenant,
            UUID bindingId,
            String type,
            String providerStableId) {
        Integer value = jdbc.queryForObject("""
                SELECT count(*)
                FROM governance.finding
                WHERE tenant_id = ? AND connector_binding_id = ?
                  AND finding_type = ? AND provider_stable_id = ?
                """, Integer.class, tenant.tenantId(), bindingId, type, providerStableId);
        return value == null ? 0 : value;
    }

    private int countOpenFindings(
            TenantContext tenant,
            UUID bindingId,
            String type,
            String providerStableId) {
        Integer value = jdbc.queryForObject("""
                SELECT count(*)
                FROM governance.finding
                WHERE tenant_id = ? AND connector_binding_id = ?
                  AND finding_type = ? AND provider_stable_id = ?
                  AND lifecycle_state = 'OPEN'
                """, Integer.class, tenant.tenantId(), bindingId, type, providerStableId);
        return value == null ? 0 : value;
    }
}
