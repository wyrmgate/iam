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
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.identity.application.PrincipalObservationReferenceQueryService;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalRepository;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationException;
import io.wyrmgate.iam.integration.application.IntegrationAdministrationFactSink;
import io.wyrmgate.iam.integration.application.IntegrationEntitlementMappingService;
import io.wyrmgate.iam.integration.application.IntegrationPrincipalMappingService;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationAdministrationRepository;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationObservationRepository;
import io.wyrmgate.iam.integration.persistence.JdbcIntegrationObservedAccessFactSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ObservedAccessDriftPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static TenantTestFixture tenants;
    private static TransactionExecutor transactions;
    private static IntegrationEntitlementMappingService mappingService;
    private static IntegrationPrincipalMappingService principalMappingService;
    private static JdbcIntegrationObservationRepository observations;
    private static GovernanceFindingRepository findings;
    private static ObservedAccessDriftEvaluationService drift;
    private static GovernanceObservationProcessingService processor;
    private static JdbcIntegrationObservedAccessFactSink observedAccessFacts;
    private static PrincipalCommandService principalCommands;
    private static io.wyrmgate.iam.governance.application.GovernancePolicyService policies;
    private static io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentRepository assignments;

    @BeforeAll
    static void start() {
        io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.reset();
        var dataSource = io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new TenantTestFixture(jdbc, ids);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var catalogRepository = new JdbcCatalogRepository(jdbc);
        var catalog = new CatalogQueryService(catalogRepository);
        var administration = new JdbcIntegrationAdministrationRepository(
                jdbc, objectMapper);
        observations = new JdbcIntegrationObservationRepository(jdbc);
        var outbox = new JdbcOutboxRepository(jdbc);
        observedAccessFacts = new JdbcIntegrationObservedAccessFactSink(outbox, ids, jdbc);
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
            public void entitlementMappingChanged(
                    TenantContext tenant, String factType, UUID mappingId, long revision,
                    Instant occurredAt, UUID correlationId) {}

            @Override
            public void principalMappingChanged(
                    TenantContext tenant, String factType, UUID mappingId, long revision,
                    Instant occurredAt, UUID correlationId) {}
        };
        mappingService = new IntegrationEntitlementMappingService(
                administration, observations, catalog, noFacts,
                observedAccessFacts, ids, transactions);

        var identityRepository = new JdbcIdentityRepository(jdbc);
        var principalRepository = new JdbcPrincipalRepository(jdbc);
        var principalReferences = new PrincipalObservationReferenceQueryService(principalRepository);
        principalCommands = new PrincipalCommandService(
                principalRepository,
                identityRepository,
                catalog,
                new JdbcPrincipalFactSink(outbox, ids),
                ids,
                transactions,
                (tenant, principalId, identityId, at) -> {});
        principalMappingService = new IntegrationPrincipalMappingService(
                administration,
                observations,
                principalReferences,
                noFacts,
                observedAccessFacts,
                ids,
                transactions);

        findings = new JdbcGovernanceFindingRepository(jdbc);
        var reporter = new GovernanceObservationReportingService(findings, ids, transactions);
        var identityRefs=new io.wyrmgate.iam.identity.application.IdentityAccessReferenceQueryService(identityRepository,principalRepository);
        var roleRepo=new io.wyrmgate.iam.catalog.persistence.JdbcRoleRepository(jdbc);
        var roles=new io.wyrmgate.iam.catalog.application.RoleExpansionQueryService(catalogRepository,roleRepo);
        var availability=new io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQueryService(catalogRepository,roleRepo,roles);
        assignments=new io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentRepository(jdbc);
        var semantics=new io.wyrmgate.iam.access.application.EffectiveAccessSemanticSupport(assignments,identityRefs,catalog,availability,roles);
        var demand=new io.wyrmgate.iam.access.application.ExactTechnicalGrantDemandQueryService(assignments,identityRefs,catalog,availability,roles,semantics);
        policies=new io.wyrmgate.iam.governance.application.GovernancePolicyService(new JdbcGovernancePolicyRepository(jdbc),catalog,identityRefs,ids,transactions);
        drift = new ObservedAccessDriftEvaluationService(observations,principalReferences,reporter,findings,identityRefs,demand,policies);
        processor = newProcessor();
    }

    @BeforeEach
    void clear() {
        jdbc.execute("""
                TRUNCATE TABLE
                    governance.finding,
                    integration.principal_observation_mapping,
                    identity.principal,
                    identity.person_profile,
                    identity.service_profile,
                    identity.workload_profile,
                    identity.identity,
                    integration.entitlement_observation_mapping,
                    integration.observed_grant,
                    integration.observed_entitlement,
                    integration.observed_principal,
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
                    tenancy.tenant
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

        trigger(tenant, binding, entitlementRun);
        processAll();
        assertThat(openFindingTypes(tenant, binding))
                .containsExactlyInAnyOrder(
                        "UNMAPPED_PROVIDER_ENTITLEMENT",
                        "UNMAPPED_PROVIDER_ENTITLEMENT",
                        "UNMAPPED_PROVIDER_GRANT_ENTITLEMENT",
                        "UNMAPPED_PROVIDER_GRANT_ENTITLEMENT");

        var mapped = mappingService.map(
                tenant, binding, "g-1", entitlement, NOW.plusSeconds(1), ids.nextId());
        processAll();

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
        processAll();

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

        var mappedBatch = processAll();
        assertThat(mappedBatch.claimed()).isEqualTo(1);
        assertThat(mappedBatch.processed()).isEqualTo(1);
        assertThat(countOpenFindings(
                tenant, binding, "UNMAPPED_PROVIDER_ENTITLEMENT", "g-1"))
                .isZero();

        var retired = mappingService.unmap(
                tenant, mapped.id(), mapped.revision(), NOW.plusSeconds(1), ids.nextId());
        var unmappedBatch = processAll();
        assertThat(unmappedBatch.claimed()).isEqualTo(1);
        assertThat(unmappedBatch.processed()).isEqualTo(1);
        assertThat(countOpenFindings(
                tenant, binding, "UNMAPPED_PROVIDER_ENTITLEMENT", "g-1"))
                .isEqualTo(1);

        mappingService.map(
                tenant, binding, "g-1", entitlement, NOW.plusSeconds(2), ids.nextId());
        var remappedBatch = processAll();
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
    void explicitPrincipalObservationMappingResolvesGrantWithoutNativeKeyGuessing() {
        TenantContext tenant = tenant("principal-drift");
        UUID applicationId = application(tenant, "app");
        UUID target = target(tenant, applicationId, "prod");
        UUID entitlement = entitlement(tenant, applicationId, target, "finance");
        UUID binding = binding(tenant, target);
        UUID entitlementRun = reconciliation(tenant, binding, "ENTITLEMENT");
        UUID principalRun = reconciliation(tenant, binding, "PRINCIPAL");
        UUID grantRun = reconciliation(tenant, binding, "GRANT");

        observedEntitlement(tenant, binding, entitlementRun, "g-1");
        observedPrincipal(tenant, binding, principalRun, "provider-account-42");
        observedGrant(
                tenant,
                binding,
                grantRun,
                "grant-1",
                "provider-account-42",
                "g-1");
        mappingService.map(
                tenant, binding, "g-1", entitlement, NOW, ids.nextId());

        var initial = processAll();
        assertThat(initial.claimed()).isEqualTo(1);
        assertThat(countOpenFindings(
                tenant, binding, "UNRESOLVED_PROVIDER_GRANT_PRINCIPAL", "grant-1"))
                .isEqualTo(1);

        var principal = principalCommands.create(
                tenant,
                target,
                "governed-native-key-is-different",
                null,
                NOW.plusSeconds(1),
                ids.nextId(),
                null);

        assertThat(processor.processAvailable().claimed()).isZero();

        var mapped = principalMappingService.map(
                tenant,
                binding,
                "provider-account-42",
                principal.id(),
                NOW.plusSeconds(2),
                ids.nextId());
        assertThat(mapped.principalId()).isEqualTo(principal.id());

        var correlated = processAll();
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

    @Test
    void boundedPagesResumeAndOnlyExactTrustedAbsenceResolvesFindings() {
        TenantContext tenant = tenant("large-drift");
        UUID application = application(tenant, "app");
        UUID target = target(tenant, application, "prod");
        UUID canonical = entitlement(tenant, application, target, "mapped");
        UUID binding = binding(tenant, target);
        UUID completeRun = reconciliation(tenant, binding, "ENTITLEMENT");
        for (int i = 0; i < 205; i++) {
            observedEntitlement(tenant, binding, completeRun, "g-" + String.format("%03d", i));
        }
        trigger(tenant, binding, completeRun);
        assertThat(processor.processAvailable().processed()).isEqualTo(1);
        assertThat(openFindingTypes(tenant, binding)).hasSize(100);

        // New generation while continuation is outstanding; restart the processor.
        mappingService.map(tenant, binding, "g-000", canonical, NOW.plusSeconds(1), ids.nextId());
        processor = newProcessor();
        processAll();
        assertThat(openFindingTypes(tenant, binding)).hasSize(204);
        assertThat(countFindings(tenant, binding, "UNMAPPED_PROVIDER_ENTITLEMENT", "g-000"))
                .isEqualTo(1);
        assertThat(countOpenFindings(tenant, binding, "UNMAPPED_PROVIDER_ENTITLEMENT", "g-000"))
                .isZero();

        // Defensively reject absent markers without exact COMPLETE coverage.
        UUID partialRun = reconciliation(tenant, binding, "ENTITLEMENT", "PARTIAL");
        jdbc.update("""
                UPDATE integration.observed_entitlement
                SET present = false, absent_at = ?, last_observed_run_id = ?
                WHERE tenant_id = ? AND connector_binding_id = ? AND provider_stable_id = 'g-204'
                """, Timestamp.from(NOW.plusSeconds(2)), partialRun, tenant.tenantId(), binding);
        trigger(tenant, binding, partialRun);
        processAll();
        assertThat(countOpenFindings(tenant, binding, "UNMAPPED_PROVIDER_ENTITLEMENT", "g-204"))
                .isEqualTo(1);

        UUID absentRun = reconciliation(tenant, binding, "ENTITLEMENT");
        jdbc.update("""
                UPDATE integration.observed_entitlement SET last_observed_run_id = ?
                WHERE tenant_id = ? AND connector_binding_id = ? AND provider_stable_id = 'g-204'
                """, absentRun, tenant.tenantId(), binding);
        trigger(tenant, binding, absentRun);
        processAll();
        assertThat(countOpenFindings(tenant, binding, "UNMAPPED_PROVIDER_ENTITLEMENT", "g-204"))
                .isZero();
        assertThat(openFindingTypes(tenant, binding)).hasSize(203);
    }

    @Test
    void mappedExcessAndCrossBindingSoDUseExactCurrentAuthorityAndTrustedAbsence(){
        var tenant=tenant("mapped-finding");
        UUID app=application(tenant,"app"),targetA=target(tenant,app,"left"),targetB=target(tenant,app,"right");
        UUID left=entitlement(tenant,app,targetA,"left"),right=entitlement(tenant,app,targetB,"right");
        UUID bindingA=binding(tenant,targetA),bindingB=binding(tenant,targetB);
        UUID subject=ids.nextId();
        new JdbcIdentityRepository(jdbc).insert(tenant,new io.wyrmgate.iam.identity.domain.Identity(subject,
                io.wyrmgate.iam.identity.domain.IdentityType.PERSON,new io.wyrmgate.iam.identity.domain.IdentityProfile.PersonProfile(),
                io.wyrmgate.iam.identity.domain.IdentityLifecycleState.ACTIVE,"Observed subject",1,NOW,NOW));
        var principalA=principalCommands.create(tenant,targetA,"native-a",subject,NOW,ids.nextId(),null);
        var principalB=principalCommands.create(tenant,targetB,"native-b",subject,NOW,ids.nextId(),null);
        UUID ea=reconciliation(tenant,bindingA,"ENTITLEMENT"),eb=reconciliation(tenant,bindingB,"ENTITLEMENT");
        UUID pa=reconciliation(tenant,bindingA,"PRINCIPAL"),pb=reconciliation(tenant,bindingB,"PRINCIPAL");
        UUID ga=reconciliation(tenant,bindingA,"GRANT"),gb=reconciliation(tenant,bindingB,"GRANT");
        observedEntitlement(tenant,bindingA,ea,"e");observedEntitlement(tenant,bindingB,eb,"e");
        observedPrincipal(tenant,bindingA,pa,"p");observedPrincipal(tenant,bindingB,pb,"p");
        observedGrant(tenant,bindingA,ga,"grant-a","p","e");observedGrant(tenant,bindingB,gb,"grant-b","p","e");
        mappingService.map(tenant,bindingA,"e",left,NOW,ids.nextId());
        mappingService.map(tenant,bindingB,"e",right,NOW,ids.nextId());
        principalMappingService.map(tenant,bindingA,"p",principalA.id(),NOW,ids.nextId());
        principalMappingService.map(tenant,bindingB,"p",principalB.id(),NOW,ids.nextId());
        var draft=policies.createDraft(tenant,io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyKind.ACCESS_REQUEST,
                io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyDecision.AUTHORIZE,
                java.util.List.of(new io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDRuleSpec("pair",left,right,
                        io.wyrmgate.iam.governance.domain.GovernancePolicyModels.RiskSeverity.HIGH,
                        io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDAction.DENY)),null,NOW);
        var ready=policies.markReady(tenant,draft.id(),draft.revision(),NOW);
        policies.activate(tenant,ready.id(),ready.revision(),NOW);
        processAll();
        assertThat(openFindingTypes(tenant,bindingA)).containsExactlyInAnyOrder("EXCESS_OBSERVED_GRANT","OBSERVED_SOD_CONFLICT");
        assertThat(openFindingTypes(tenant,bindingB)).containsExactlyInAnyOrder("EXCESS_OBSERVED_GRANT","OBSERVED_SOD_CONFLICT");
        var sod=findings.findOpenPage(tenant,bindingA,null,10).stream().filter(f->f.findingType().equals("OBSERVED_SOD_CONFLICT")).findFirst().orElseThrow();
        assertThat(sod.context().identityId()).isEqualTo(subject);
        assertThat(sod.context().relatedBindingId()).isEqualTo(bindingB);
        assertThat(sod.context().relatedPrincipalId()).isEqualTo(principalB.id());
        var other=tenant("other-finding");
        assertThat(findings.findById(other,sod.id())).isEmpty();

        // No EffectiveAccess or DesiredGrant projection has been created.
        assignments.insert(tenant,new io.wyrmgate.iam.access.domain.AccessAssignment(ids.nextId(),subject,1,
                io.wyrmgate.iam.access.domain.AccessAssignment.TargetKind.ENTITLEMENT,null,left,
                io.wyrmgate.iam.access.domain.AccessAssignment.PrincipalConstraintKind.SPECIFIC,principalA.id(),
                io.wyrmgate.iam.access.domain.AccessAssignment.ProvenanceKind.MANUAL,null,
                io.wyrmgate.iam.access.domain.AccessAssignment.LifecycleState.ACTIVE,null,null,1,NOW,NOW));
        refresh(tenant);
        assertThat(openFindingTypes(tenant,bindingA)).containsExactly("OBSERVED_SOD_CONFLICT");
        assertThat(openFindingTypes(tenant,bindingB)).contains("EXCESS_OBSERVED_GRANT");

        UUID partial=reconciliation(tenant,bindingB,"GRANT","PARTIAL");
        jdbc.update("""
                UPDATE integration.observed_grant SET present=false,absent_at=?,last_observed_run_id=?
                WHERE tenant_id=? AND connector_binding_id=? AND provider_stable_id='grant-b'
                """,Timestamp.from(NOW.plusSeconds(1)),partial,tenant.tenantId(),bindingB);
        trigger(tenant,bindingB,partial);processAll();refresh(tenant);
        assertThat(findings.findById(tenant,sod.id()).orElseThrow().lifecycleState()).isEqualTo("OPEN");
        UUID complete=reconciliation(tenant,bindingB,"GRANT");
        jdbc.update("UPDATE integration.observed_grant SET last_observed_run_id=? WHERE tenant_id=? AND connector_binding_id=? AND provider_stable_id='grant-b'",
                complete,tenant.tenantId(),bindingB);
        trigger(tenant,bindingB,complete);processAll();refresh(tenant);
        assertThat(findings.findById(tenant,sod.id()).orElseThrow().lifecycleState()).isEqualTo("RESOLVED");
    }

    private static void refresh(TenantContext tenant){
        jdbc.update("UPDATE governance.observation_evaluation SET updated_at=? WHERE tenant_id=?",Timestamp.from(NOW),tenant.tenantId());
        assertThat(processor.refreshDue()).isPositive();
        processAll();
    }

    private static GovernanceObservationProcessingService newProcessor() {
        return new GovernanceObservationProcessingService(new JdbcOutboxRepository(jdbc), drift,
                observations, new JdbcGovernanceObservationEvaluationRepository(jdbc),
                transactions, ids, new ObjectMapper().findAndRegisterModules());
    }

    private static GovernanceObservationProcessingService.ProcessingBatchResult processAll() {
        var first = processor.processAvailable();
        assertThat(first.failed()).isZero();
        for (int i = 0; i < 30; i++) {
            var batch = processor.processAvailable();
            assertThat(batch.failed()).isZero();
            if (batch.claimed() == 0) return first;
        }
        throw new AssertionError("observation evaluation did not converge");
    }

    private static void trigger(TenantContext tenant, UUID binding, UUID run) {
        transactions.required(() -> observedAccessFacts.inputChanged(tenant, binding,
                io.wyrmgate.iam.integration.application.IntegrationObservedAccessFactSink.SourceKind.RECONCILIATION_RUN,
                run, 1, NOW, ids.nextId()));
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.createActive(name, NOW).id());
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
                    code, lifecycle_state, revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)
                """,
                id, tenant.tenantId(), applicationId, targetId,
                code, Timestamp.from(NOW), Timestamp.from(NOW));
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
        return reconciliation(tenant, bindingId, objectClass, "COMPLETE");
    }

    private UUID reconciliation(
            TenantContext tenant, UUID bindingId, String objectClass, String coverage) {
        UUID id = ids.nextId();
        jdbc.update("""
                INSERT INTO integration.reconciliation_run (
                    id, tenant_id, connector_binding_id, operation_id,
                    scope_object_class, state, reported_coverage,
                    effective_completeness, configuration_version,
                    runtime_id, runtime_version, contract_id, contract_version,
                    correlation_id, revision, started_at, completed_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?,
                        1, 'runtime.test', '1.0', 'test.group', 1,
                        ?, 1, ?, ?, ?, ?)
                """,
                id, tenant.tenantId(), bindingId, ids.nextId(), objectClass, coverage, coverage,
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

    private void observedPrincipal(
            TenantContext tenant,
            UUID bindingId,
            UUID runId,
            String providerId) {
        jdbc.update("""
                INSERT INTO integration.observed_principal (
                    id, tenant_id, connector_binding_id, provider_stable_id,
                    provider_version, observed_state, present,
                    last_observed_run_id, observed_at, absent_at)
                VALUES (?, ?, ?, ?, 'v1', '{}'::jsonb, true, ?, ?, NULL)
                """,
                ids.nextId(),
                tenant.tenantId(),
                bindingId,
                providerId,
                runId,
                Timestamp.from(NOW));
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
