package io.wyrmgate.iam.catalog.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.AccessAssignmentCommandService;
import io.wyrmgate.iam.access.application.AccessAssignmentFactSink;
import io.wyrmgate.iam.access.application.AccessTargetReductionIntakeService;
import io.wyrmgate.iam.access.application.AccessTargetReductionProcessingService;
import io.wyrmgate.iam.access.application.EffectiveAccessQueryService;
import io.wyrmgate.iam.access.application.EffectiveAccessSemanticSupport;
import io.wyrmgate.iam.access.application.TerminalCatalogAssignmentRepairService;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentRepository;
import io.wyrmgate.iam.access.persistence.JdbcAccessTargetReductionRepository;
import io.wyrmgate.iam.access.persistence.JdbcAccessTargetReductionWorkSink;
import io.wyrmgate.iam.access.persistence.JdbcEffectiveAccessRepository;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQueryService;
import io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationService;
import io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationStarter;
import io.wyrmgate.iam.catalog.application.RoleCommandService;
import io.wyrmgate.iam.catalog.application.RoleExpansionQueryService;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetAvailability;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class CatalogAccessTargetAvailabilityIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static TenantTestFixture tenants;
    private static TransactionExecutor transactions;
    private static JdbcOutboxRepository outbox;
    private static JdbcCatalogRepository catalogRepository;
    private static JdbcRoleRepository roleRepository;
    private static io.wyrmgate.iam.catalog.application.CatalogCommandService catalog;
    private static RoleCommandService roles;
    private static CatalogAccessTargetAvailabilityQuery availability;
    private static CatalogAvailabilityPropagationService propagation;
    private static JdbcAccessAssignmentRepository assignments;
    private static JdbcEffectiveAccessRepository effective;
    private static AccessAssignmentCommandService assignmentCommands;
    private static AccessTargetReductionIntakeService reductionIntake;
    private static AccessTargetReductionProcessingService reductionProcessing;

    @BeforeAll
    static void start() {
        SharedPostgresIntegrationDatabase.reset();
        var dataSource = SharedPostgresIntegrationDatabase.dataSource();
        jdbc = SharedPostgresIntegrationDatabase.jdbc();
        ids = new UuidV7Generator();
        tenants = new TenantTestFixture(jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        outbox = new JdbcOutboxRepository(jdbc);

        catalogRepository = new JdbcCatalogRepository(jdbc);
        roleRepository = new JdbcRoleRepository(jdbc);
        var propagationRepository =
                new JdbcCatalogAvailabilityPropagationRepository(jdbc);
        var propagationWork =
                new JdbcCatalogAvailabilityPropagationWorkSink(outbox, ids);
        var availabilityFacts =
                new JdbcCatalogAccessTargetAvailabilityFactSink(
                        outbox, ids, new ObjectMapper());
        var roleFacts = new JdbcRoleExpansionFactSink(outbox, ids);
        var starter = new CatalogAvailabilityPropagationStarter(
                propagationRepository, propagationWork, ids, transactions);
        var expansion = new RoleExpansionQueryService(
                catalogRepository, roleRepository);
        availability = new CatalogAccessTargetAvailabilityQueryService(
                catalogRepository, roleRepository, expansion);
        catalog = new io.wyrmgate.iam.catalog.application.CatalogCommandService(
                catalogRepository, starter, ids, transactions);
        roles = new RoleCommandService(
                catalogRepository,
                roleRepository,
                roleFacts,
                starter,
                ids,
                transactions);
        propagation = new CatalogAvailabilityPropagationService(
                outbox,
                propagationRepository,
                availabilityFacts,
                roleFacts,
                propagationWork,
                transactions);

        assignments = new JdbcAccessAssignmentRepository(jdbc);
        effective = new JdbcEffectiveAccessRepository(jdbc, ids);
        IdentityAccessReferenceQuery identityReferences = alwaysEligibleIdentity();
        AccessAssignmentFactSink assignmentFacts = (tenant, assignment) -> {};
        assignmentCommands = new AccessAssignmentCommandService(
                assignments,
                identityReferences,
                new io.wyrmgate.iam.catalog.application.CatalogQueryService(
                        catalogRepository),
                availability,
                expansion,
                assignmentFacts,
                (tenant, assignment, now) -> {},
                ids,
                transactions);

        var reductionRepository =
                new JdbcAccessTargetReductionRepository(jdbc);
        var reductionWork =
                new JdbcAccessTargetReductionWorkSink(outbox, ids);
        reductionIntake = new AccessTargetReductionIntakeService(
                outbox,
                reductionRepository,
                reductionWork,
                availability,
                ids,
                transactions,
                new ObjectMapper());
        reductionProcessing = new AccessTargetReductionProcessingService(
                outbox,
                reductionRepository,
                assignments,
                assignmentCommands,
                availability,
                reductionWork,
                transactions,
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
    }

    @BeforeEach
    void clear() {
        jdbc.execute("""
                TRUNCATE TABLE
                    access.access_target_reduction,
                    access.effective_access_support_role_version,
                    access.effective_access_support,
                    access.effective_access,
                    access.access_assignment,
                    catalog.access_target_availability_propagation,
                    catalog.role_version_member,
                    catalog.role_version,
                    catalog.role,
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    platform.outbox_event,
                    tenancy.tenant
                CASCADE
                """);
    }

    @Test
    void applicationRetirementSuppressesAccessImmediatelyAndReducesOnlyTerminalTargets() {
        TenantContext tenant = tenant("catalog-availability");
        UUID identityId = ids.nextId();
        var app = catalog.createApplication(tenant, "finance", "Finance", NOW);
        var target = catalog.createTarget(tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant, app.id(), target.id(), "report", NOW);

        Role applicationRole = roles.createRole(
                tenant,
                Role.RoleType.APPLICATION,
                app.id(),
                "analyst",
                "Analyst",
                NOW);
        var appVersion = activate(
                tenant,
                applicationRole.id(),
                List.of(RoleCommandService.MemberSpec.entitlement(entitlement.id())),
                NOW.plusSeconds(1));

        Role businessRole = roles.createRole(
                tenant,
                Role.RoleType.BUSINESS,
                null,
                "finance-user",
                "Finance User",
                NOW.plusSeconds(4));
        var businessVersion = activate(
                tenant,
                businessRole.id(),
                List.of(RoleCommandService.MemberSpec.applicationRole(applicationRole.id())),
                NOW.plusSeconds(5));

        AccessAssignment direct = assignment(
                identityId,
                AccessAssignment.TargetKind.ENTITLEMENT,
                null,
                entitlement.id(),
                AccessAssignment.LifecycleState.ACTIVE,
                NOW.minusSeconds(10),
                null);
        AccessAssignment future = assignment(
                identityId,
                AccessAssignment.TargetKind.ENTITLEMENT,
                null,
                entitlement.id(),
                AccessAssignment.LifecycleState.SCHEDULED,
                NOW.plusSeconds(86400),
                null);
        AccessAssignment ended = assignment(
                identityId,
                AccessAssignment.TargetKind.ENTITLEMENT,
                null,
                entitlement.id(),
                AccessAssignment.LifecycleState.ACTIVE,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(1));
        AccessAssignment applicationRoleAssignment = assignment(
                identityId,
                AccessAssignment.TargetKind.ROLE,
                applicationRole.id(),
                null,
                AccessAssignment.LifecycleState.ACTIVE,
                NOW.minusSeconds(10),
                null);
        AccessAssignment businessRoleAssignment = assignment(
                identityId,
                AccessAssignment.TargetKind.ROLE,
                businessRole.id(),
                null,
                AccessAssignment.LifecycleState.ACTIVE,
                NOW.minusSeconds(10),
                null);
        for (AccessAssignment value : List.of(
                direct, future, ended, applicationRoleAssignment, businessRoleAssignment)) {
            assignments.insert(tenant, value);
        }

        effective.applyDirectAssignment(
                tenant, direct, "ANY", "direct", NOW.minusSeconds(5));
        effective.replaceRoleAssignmentSupport(
                tenant,
                businessRoleAssignment,
                "ANY",
                List.of(new io.wyrmgate.iam.access.application.EffectiveAccessRepository.RoleSupportPath(
                        entitlement.id(),
                        List.of(businessVersion.id(), appVersion.id()),
                        "business-path")),
                NOW.minusSeconds(5));

        var semantic = new EffectiveAccessQueryService(
                effective,
                alwaysEligibleIdentity(),
                new EffectiveAccessSemanticSupport(
                        assignments,
                        alwaysEligibleIdentity(),
                        new io.wyrmgate.iam.catalog.application.CatalogQueryService(
                                catalogRepository),
                        availability,
                        new RoleExpansionQueryService(catalogRepository, roleRepository)));
        assertThat(semantic.find(
                tenant, identityId, entitlement.id(), "ANY", NOW)).isPresent();

        var retired = catalog.retireApplication(
                tenant, app.id(), app.revision(), NOW.plusSeconds(10));

        assertThat(availability.currentAvailability(
                        tenant, CatalogAccessTargetKind.ENTITLEMENT, entitlement.id()))
                .get()
                .extracting(CatalogAccessTargetAvailability::status)
                .isEqualTo(CatalogAccessTargetAvailability.Status.TERMINALLY_UNAVAILABLE);
        assertThat(availability.currentAvailability(
                        tenant, CatalogAccessTargetKind.ROLE, applicationRole.id()))
                .get()
                .extracting(CatalogAccessTargetAvailability::status)
                .isEqualTo(CatalogAccessTargetAvailability.Status.TERMINALLY_UNAVAILABLE);
        assertThat(availability.currentAvailability(
                        tenant, CatalogAccessTargetKind.ROLE, businessRole.id()))
                .get()
                .extracting(CatalogAccessTargetAvailability::status)
                .isEqualTo(CatalogAccessTargetAvailability.Status.TEMPORARILY_UNAVAILABLE);

        assertThat(semantic.find(
                tenant, identityId, entitlement.id(), "ANY", NOW.plusSeconds(10)))
                .isEmpty();

        assertThatThrownBy(() -> assignmentCommands.createEntitlementAssignment(
                        tenant,
                        identityId,
                        entitlement.id(),
                        AccessAssignment.PrincipalConstraintKind.ANY,
                        null,
                        null,
                        null,
                        NOW.plusSeconds(11)))
                .isInstanceOf(io.wyrmgate.iam.access.application.AccessAssignmentCommandException.class)
                .hasMessageContaining("not currently assignable");

        assertThatThrownBy(() -> roles.createDraftVersion(
                        tenant,
                        applicationRole.id(),
                        List.of(RoleCommandService.MemberSpec.entitlement(entitlement.id())),
                        NOW.plusSeconds(12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("application is retired");

        drainCatalogPropagation();
        drainReductionIntake();
        drainReductions();

        assertThat(assignments.findById(tenant, direct.id()).orElseThrow().lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.REVOKED);
        assertThat(assignments.findById(tenant, future.id()).orElseThrow().lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.CANCELLED);
        assertThat(assignments.findById(tenant, ended.id()).orElseThrow().lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.EXPIRED);
        assertThat(assignments.findById(
                        tenant, applicationRoleAssignment.id()).orElseThrow().lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.REVOKED);
        assertThat(assignments.findById(
                        tenant, businessRoleAssignment.id()).orElseThrow().lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.ACTIVE);

        assertThat(retired.revision()).isEqualTo(app.revision() + 1);
    }

    @Test
    void lateAssignmentFromAdmissionRaceIsTerminatedByItsNormalRepairPath() {
        TenantContext tenant = tenant("late-catalog-race");
        UUID identityId = ids.nextId();
        var app = catalog.createApplication(
                tenant, "late-race", "Late Race", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant, app.id(), target.id(), "read", NOW);

        // Model a request that completed its Catalog validation before retirement,
        // then committed after the bulk reduction had already drained.
        catalog.retireApplication(
                tenant, app.id(), app.revision(), NOW.plusSeconds(1));
        drainCatalogPropagation();
        drainReductionIntake();
        drainReductions();

        AccessAssignment late = assignment(
                identityId,
                AccessAssignment.TargetKind.ENTITLEMENT,
                null,
                entitlement.id(),
                AccessAssignment.LifecycleState.ACTIVE,
                NOW.minusSeconds(1),
                null);
        assignments.insert(tenant, late);

        var repaired = new TerminalCatalogAssignmentRepairService(
                assignments,
                availability,
                assignmentCommands)
                .repairIfTerminal(
                        tenant, late.id(), NOW.plusSeconds(2));

        assertThat(repaired.lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.REVOKED);
        assertThat(assignments.findById(
                        tenant, late.id()).orElseThrow().lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.REVOKED);
    }

    @Test
    void targetReductionPagesRestartsAndFencesSupersededTerminalCause() {
        TenantContext tenant = tenant("paged-target-reduction");
        var app = catalog.createApplication(tenant, "paged-app", "Paged", NOW);
        var target = catalog.createTarget(tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant, app.id(), target.id(), "member", NOW);
        UUID identityId = ids.nextId();
        for (int i = 0; i < 205; i++) {
            assignments.insert(tenant, assignment(
                    identityId, AccessAssignment.TargetKind.ENTITLEMENT,
                    null, entitlement.id(), AccessAssignment.LifecycleState.ACTIVE,
                    NOW.minusSeconds(1), null));
        }

        catalog.retireTarget(tenant, target.id(), target.revision(), NOW.plusSeconds(1));
        drainCatalogPropagation();
        drainReductionIntake();
        UUID oldReductionId = jdbc.queryForObject("""
                SELECT id FROM access.access_target_reduction
                WHERE tenant_id = ? AND target_id = ? AND source_cause_kind = 'APPLICATION_TARGET'
                """, UUID.class, tenant.tenantId(), entitlement.id());
        var reductionRepository = new JdbcAccessTargetReductionRepository(jdbc);
        var reductionWork = new JdbcAccessTargetReductionWorkSink(outbox, ids);
        // Use a new processor instance for each page; progress is durable.
        // Intake and Catalog propagation use the runtime clock; reduction progress
        // must not be timestamped before its durable process was created.
        var clock = Clock.systemUTC();
        new AccessTargetReductionProcessingService(outbox, reductionRepository,
                assignments, assignmentCommands, availability, reductionWork,
                transactions, clock).processAvailable();
        var first = reductionRepository.findById(tenant, oldReductionId).orElseThrow();
        assertThat(first.state()).isEqualTo(
                io.wyrmgate.iam.access.domain.AccessTargetReduction.State.RUNNING);
        assertThat(first.processedAssignmentCount()).isEqualTo(200);
        assertThat(first.afterAssignmentId()).isNotNull();

        // Application retirement supersedes the exact target-retirement cause.
        catalog.retireApplication(tenant, app.id(), app.revision(), NOW.plusSeconds(2));
        drainCatalogPropagation();
        drainReductionIntake();
        UUID newReductionId = jdbc.queryForObject("""
                SELECT id FROM access.access_target_reduction
                WHERE tenant_id = ? AND target_id = ? AND source_cause_kind = 'APPLICATION'
                """, UUID.class, tenant.tenantId(), entitlement.id());

        // Replayed stale work must not terminate against the superseded cause.
        reductionWork.requested(tenant, first, ids.nextId(), null);
        for (int i = 0; i < 4; i++) {
            new AccessTargetReductionProcessingService(outbox, reductionRepository,
                    assignments, assignmentCommands, availability, reductionWork,
                    transactions, clock).processAvailable();
        }
        var oldCompleted = reductionRepository.findById(tenant, oldReductionId).orElseThrow();
        var newCompleted = reductionRepository.findById(tenant, newReductionId).orElseThrow();
        assertThat(oldCompleted.state()).isEqualTo(
                io.wyrmgate.iam.access.domain.AccessTargetReduction.State.COMPLETED);
        assertThat(oldCompleted.processedAssignmentCount()).isEqualTo(200);
        assertThat(newCompleted.state()).isEqualTo(
                io.wyrmgate.iam.access.domain.AccessTargetReduction.State.COMPLETED);
        assertThat(newCompleted.processedAssignmentCount()).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM access.access_assignment
                WHERE tenant_id = ? AND entitlement_id = ? AND lifecycle_state = 'REVOKED'
                """, Integer.class, tenant.tenantId(), entitlement.id())).isEqualTo(205);
        reductionWork.requested(tenant, newCompleted, ids.nextId(), null);
        drainReductions();
        assertThat(reductionRepository.findById(tenant, newReductionId).orElseThrow())
                .isEqualTo(newCompleted);
    }

    private static void drainCatalogPropagation() {
        for (int i = 0; i < 20; i++) {
            var batch = propagation.processAvailable();
            if (batch.claimed() == 0) return;
        }
        throw new AssertionError("Catalog propagation did not drain");
    }

    private static void drainReductionIntake() {
        for (int i = 0; i < 20; i++) {
            var batch = reductionIntake.processAvailable();
            if (batch.claimed() == 0) return;
        }
        throw new AssertionError("target reduction intake did not drain");
    }

    private static void drainReductions() {
        for (int i = 0; i < 20; i++) {
            var batch = reductionProcessing.processAvailable();
            if (batch.claimed() == 0) return;
        }
        throw new AssertionError("target reductions did not drain");
    }

    private static io.wyrmgate.iam.catalog.domain.RoleVersion activate(
            TenantContext tenant,
            UUID roleId,
            List<RoleCommandService.MemberSpec> members,
            Instant at) {
        var draft = roles.createDraftVersion(tenant, roleId, members, at);
        roles.markReady(tenant, draft.id(), draft.revision(), at.plusSeconds(1));
        return roles.activate(
                tenant, draft.id(), draft.revision() + 1, at.plusSeconds(2));
    }

    private static AccessAssignment assignment(
            UUID identityId,
            AccessAssignment.TargetKind targetKind,
            UUID roleId,
            UUID entitlementId,
            AccessAssignment.LifecycleState lifecycleState,
            Instant validFrom,
            Instant validUntil) {
        return new AccessAssignment(
                ids.nextId(),
                identityId,
                1,
                targetKind,
                roleId,
                entitlementId,
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                AccessAssignment.ProvenanceKind.MANUAL,
                null,
                lifecycleState,
                validFrom,
                validUntil,
                1,
                NOW,
                NOW);
    }

    private static IdentityAccessReferenceQuery alwaysEligibleIdentity() {
        return new IdentityAccessReferenceQuery() {
            @Override
            public boolean identityExists(TenantContext tenant, UUID identityId) {
                return true;
            }

            @Override
            public IdentityAccessStatus accessStatus(
                    TenantContext tenant, UUID identityId) {
                return IdentityAccessStatus.eligible("ACTIVE", 1, 1);
            }

            @Override
            public PrincipalReference principal(
                    TenantContext tenant, UUID principalId) {
                return PrincipalReference.notFound();
            }

            @Override
            public PrincipalSelection selectUniqueActivePrincipal(
                    TenantContext tenant, UUID identityId, UUID applicationTargetId) {
                return PrincipalSelection.none();
            }
        };
    }

    private static TenantContext tenant(String name) {
        return new TenantContext(tenants.createActive(name, NOW).id());
    }
}
