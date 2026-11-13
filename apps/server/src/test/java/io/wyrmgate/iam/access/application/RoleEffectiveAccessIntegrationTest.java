package io.wyrmgate.iam.access.application;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentBoundaryScheduler;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentFactSink;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentRepository;
import io.wyrmgate.iam.access.persistence.JdbcDesiredStateProjectionRepository;
import io.wyrmgate.iam.access.persistence.JdbcEffectiveAccessRepository;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.RoleCommandService;
import io.wyrmgate.iam.catalog.application.RoleExpansionQueryService;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogRepository;
import io.wyrmgate.iam.catalog.persistence.JdbcRoleExpansionFactSink;
import io.wyrmgate.iam.catalog.persistence.JdbcRoleRepository;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQueryService;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityFactSink;
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.identity.application.PrincipalFactSink;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
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

class RoleEffectiveAccessIntegrationTest {

    private static final Instant NOW =
            Instant.parse("2026-09-25T12:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static TenantTestFixture tenants;
    private static TransactionExecutor transactions;
    private static IdentityCommandService identities;
    private static PrincipalCommandService principals;
    private static CatalogCommandService catalog;
    private static RoleCommandService roles;
    private static RoleExpansionQueryService expansion;
    private static io.wyrmgate.iam.catalog.application.CatalogQueryService catalogQuery;
    private static io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery catalogAvailability;
    private static IdentityAccessReferenceQueryService identityReferences;
    private static JdbcOutboxRepository outbox;
    private static JdbcScheduledWorkRepository scheduledWork;
    private static JdbcAccessAssignmentRepository assignmentRepository;
    private static JdbcEffectiveAccessRepository effectiveRepository;
    private static JdbcDesiredStateProjectionRepository desiredRepository;
    private static AccessAssignmentCommandService assignments;
    private static DesiredStateDerivationService desiredDerivation;
    private static io.wyrmgate.iam.catalog.application.CatalogAvailabilityPropagationService
            catalogPropagation;
    private static EffectiveAccessQuery effectiveQuery;

    @BeforeAll
    static void start() {
        io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.reset();
        var dataSource = io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new TenantTestFixture(jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        outbox = new JdbcOutboxRepository(jdbc);
        scheduledWork = new JdbcScheduledWorkRepository(jdbc, ids);

        var identityRepository = new JdbcIdentityRepository(jdbc);
        IdentityFactSink noIdentityFacts = new IdentityFactSink() {
            @Override
            public void identityCreated(
                    TenantContext tenant,
                    Identity identity,
                    UUID correlationId,
                    UUID causationId) {
            }

            @Override
            public void displayNameChanged(
                    TenantContext tenant,
                    Identity identity,
                    UUID correlationId,
                    UUID causationId) {
            }


            @Override
            public void lifecycleChanged(
                    TenantContext tenant,
                    io.wyrmgate.iam.identity.domain.IdentityLifecycleState previousState,
                    Identity identity,
                    java.util.UUID correlationId,
                    java.util.UUID causationId) {
            }
        };
        identities = new IdentityCommandService(
                identityRepository, noIdentityFacts, ids, transactions,
                (tenant, identityId, at) -> {});

        var catalogRepository = new JdbcCatalogRepository(jdbc);
        var roleRepository = new JdbcRoleRepository(jdbc);
        catalog = new CatalogCommandService(
                catalogRepository,
                io.wyrmgate.iam.testsupport.CatalogAccessTestSupport.propagationStarter(
                        jdbc, ids, transactions),
                ids,
                transactions);
        roles = new RoleCommandService(
                catalogRepository,
                roleRepository,
                new JdbcRoleExpansionFactSink(outbox, ids),
                io.wyrmgate.iam.testsupport.CatalogAccessTestSupport.propagationStarter(
                        jdbc, ids, transactions),
                ids,
                transactions);
        expansion = new RoleExpansionQueryService(
                catalogRepository, roleRepository);
        catalogPropagation =
                io.wyrmgate.iam.testsupport.CatalogAccessTestSupport.propagationService(
                        jdbc, ids, transactions);
        catalogAvailability =
                io.wyrmgate.iam.testsupport.CatalogAccessTestSupport.availability(
                        catalogRepository, roleRepository);
        catalogQuery =
                new io.wyrmgate.iam.catalog.application.CatalogQueryService(
                        catalogRepository);

        var principalRepository = new JdbcPrincipalRepository(jdbc);
        PrincipalFactSink noPrincipalFacts = new PrincipalFactSink() {
            @Override
            public void principalCorrelated(
                    TenantContext tenant,
                    io.wyrmgate.iam.identity.domain.Principal principal,
                    java.util.UUID correlationId,
                    java.util.UUID causationId) {
            }

            @Override
            public void principalAccessProjectionChanged(
                    TenantContext tenant,
                    io.wyrmgate.iam.identity.domain.Principal principal,
                    java.util.UUID correlationId,
                    java.util.UUID causationId) {
            }

            @Override
            public void principalReassigned(
                    TenantContext tenant,
                    io.wyrmgate.iam.identity.domain.Principal principal,
                    java.util.UUID previousIdentityId,
                    java.util.UUID correlationId,
                    java.util.UUID causationId) {
            }
        };
        principals = new PrincipalCommandService(
                principalRepository,
                identityRepository,
                new io.wyrmgate.iam.catalog.application.CatalogQueryService(
                        catalogRepository),
                noPrincipalFacts,
                ids,
                transactions,
                (tenant, principalId, identityId, at) -> {});
        identityReferences = new IdentityAccessReferenceQueryService(
                identityRepository, principalRepository);

        assignmentRepository = new JdbcAccessAssignmentRepository(jdbc);
        effectiveRepository = new JdbcEffectiveAccessRepository(jdbc, ids);
        desiredRepository = new JdbcDesiredStateProjectionRepository(jdbc, ids);
        effectiveQuery = new EffectiveAccessQueryService(
                effectiveRepository,
                identityReferences,
                new EffectiveAccessSemanticSupport(
                        assignmentRepository,
                        identityReferences,
                        catalogQuery,
                        catalogAvailability,
                        expansion));
        desiredDerivation = new DesiredStateDerivationService(
                effectiveQuery,
                desiredRepository,
                catalogQuery,
                identityReferences,
                (tenant, state) -> { },
                transactions);
        assignments = new AccessAssignmentCommandService(
                assignmentRepository,
                identityReferences,
                catalogQuery,
                catalogAvailability,
                expansion,
                new JdbcAccessAssignmentFactSink(outbox, ids),
                new JdbcAccessAssignmentBoundaryScheduler(scheduledWork),
                ids,
                transactions);
    }

    @BeforeEach
    void clear() {
        jdbc.execute("""
                TRUNCATE TABLE
                    access.effective_access_support_role_version,
                    access.effective_access_support,
                    access.effective_access,
                    access.access_assignment,
                    access.desired_grant_state,
                    access.desired_principal_state,
                    platform.scheduled_work,
                    platform.outbox_event,
                    identity.principal,
                    identity.person_profile,
                    identity.service_profile,
                    identity.workload_profile,
                    identity.identity,
                    catalog.role_version_member,
                    catalog.role_version,
                    catalog.role,
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    tenancy.tenant
                CASCADE
                """);
    }

    @Test
    void businessRoleAssignmentTracksCurrentNestedRoleVersionsAndExplainablePaths() {
        TenantContext tenant = tenant("role-access");
        Identity identity = identity(tenant, "Role User");

        var appOne = catalog.createApplication(
                tenant, "one", "One", NOW);
        var targetOne = catalog.createTarget(
                tenant, appOne.id(), "prod", NOW);
        var e1 = catalog.createEntitlement(tenant, appOne.id(), targetOne.id(), "one-read", NOW);
        var e1Next = catalog.createEntitlement(tenant, appOne.id(), targetOne.id(), "one-write", NOW);

        var appTwo = catalog.createApplication(
                tenant, "two", "Two", NOW);
        var targetTwo = catalog.createTarget(
                tenant, appTwo.id(), "prod", NOW);
        var e2 = catalog.createEntitlement(tenant, appTwo.id(), targetTwo.id(), "two-read", NOW);

        Role applicationRole = roles.createRole(
                tenant,
                Role.RoleType.APPLICATION,
                appOne.id(),
                "app-one-users",
                "App One Users",
                NOW);
        var appV1 = activate(
                tenant,
                applicationRole.id(),
                List.of(RoleCommandService.MemberSpec.entitlement(e1.id())),
                NOW.plusSeconds(1));

        Role businessRole = roles.createRole(
                tenant,
                Role.RoleType.BUSINESS,
                null,
                "employee",
                "Employee",
                NOW.plusSeconds(4));
        var businessV1 = activate(
                tenant,
                businessRole.id(),
                List.of(
                        RoleCommandService.MemberSpec.applicationRole(
                                applicationRole.id()),
                        RoleCommandService.MemberSpec.entitlement(e2.id())),
                NOW.plusSeconds(5));

        AccessAssignment assignment = assignments.createRoleAssignment(
                tenant,
                identity.id(),
                businessRole.id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                null,
                null,
                NOW.plusSeconds(8));

        process(NOW.plusSeconds(9));

        assertThat(effectiveQuery.find(
                tenant, identity.id(), e1.id(), "ANY",
                NOW.plusSeconds(9))).isPresent();
        assertThat(effectiveQuery.find(
                tenant, identity.id(), e2.id(), "ANY",
                NOW.plusSeconds(9))).isPresent();

        UUID e1Support = jdbc.queryForObject("""
                SELECT s.id
                FROM access.effective_access_support s
                JOIN access.effective_access ea
                  ON ea.tenant_id = s.tenant_id
                 AND ea.id = s.effective_access_id
                WHERE s.tenant_id = ?
                  AND s.access_assignment_id = ?
                  AND ea.entitlement_id = ?
                """, UUID.class,
                tenant.tenantId(), assignment.id(), e1.id());
        assertThat(jdbc.queryForList("""
                SELECT role_version_id
                FROM access.effective_access_support_role_version
                WHERE tenant_id = ?
                  AND effective_access_support_id = ?
                ORDER BY path_ordinal
                """, UUID.class, tenant.tenantId(), e1Support))
                .containsExactly(businessV1.id(), appV1.id());

        activate(
                tenant,
                applicationRole.id(),
                List.of(RoleCommandService.MemberSpec.entitlement(
                        e1Next.id())),
                NOW.plusSeconds(10));

        // Current Role expansion is authoritative immediately. The materialized
        // EffectiveAccess rows still describe the old path until propagation runs.
        assertThat(effectiveQuery.find(
                tenant, identity.id(), e1.id(), "ANY",
                NOW.plusSeconds(12))).isEmpty();
        assertThat(effectiveQuery.find(
                tenant, identity.id(), e1Next.id(), "ANY",
                NOW.plusSeconds(12))).isEmpty();
        assertThat(effectiveQuery.find(
                tenant, identity.id(), e2.id(), "ANY",
                NOW.plusSeconds(12))).isPresent();

        drainCatalogPropagation();
        process(NOW.plusSeconds(13));

        assertThat(effectiveQuery.find(
                tenant, identity.id(), e1.id(), "ANY",
                NOW.plusSeconds(13))).isEmpty();
        assertThat(effectiveQuery.find(
                tenant, identity.id(), e1Next.id(), "ANY",
                NOW.plusSeconds(13))).isPresent();
        assertThat(effectiveQuery.find(
                tenant, identity.id(), e2.id(), "ANY",
                NOW.plusSeconds(13))).isPresent();

        assignments.terminate(
                tenant,
                assignment.id(),
                assignment.revision(),
                NOW.plusSeconds(14));
        process(NOW.plusSeconds(14));

        assertThat(effectiveQuery.find(
                tenant, identity.id(), e1Next.id(), "ANY",
                NOW.plusSeconds(14))).isEmpty();
        assertThat(effectiveQuery.find(
                tenant, identity.id(), e2.id(), "ANY",
                NOW.plusSeconds(14))).isEmpty();
    }

    @Test
    void specificRoleAssignmentRequiresExactlyOneMatchingTarget() {
        TenantContext tenant = tenant("specific-role");
        Identity identity = identity(tenant, "Specific Role User");

        var app = catalog.createApplication(
                tenant, "app", "App", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(tenant, app.id(), target.id(), "read", NOW);
        Role applicationRole = roles.createRole(
                tenant,
                Role.RoleType.APPLICATION,
                app.id(),
                "users",
                "Users",
                NOW);
        activate(
                tenant,
                applicationRole.id(),
                List.of(RoleCommandService.MemberSpec.entitlement(
                        entitlement.id())),
                NOW.plusSeconds(1));

        var principal = principals.create(
                tenant,
                target.id(),
                "specific-role-user",
                identity.id(),
                NOW.plusSeconds(4),
                ids.nextId(),
                null);

        AccessAssignment accepted = assignments.createRoleAssignment(
                tenant,
                identity.id(),
                applicationRole.id(),
                AccessAssignment.PrincipalConstraintKind.SPECIFIC,
                principal.id(),
                null,
                null,
                NOW.plusSeconds(5));
        assertThat(accepted.roleId()).isEqualTo(applicationRole.id());

        var secondApp = catalog.createApplication(
                tenant, "other", "Other", NOW);
        var secondTarget = catalog.createTarget(
                tenant, secondApp.id(), "prod", NOW);
        var secondEntitlement = catalog.createEntitlement(tenant, secondApp.id(), secondTarget.id(), "read", NOW);
        Role businessRole = roles.createRole(
                tenant,
                Role.RoleType.BUSINESS,
                null,
                "multi",
                "Multi",
                NOW.plusSeconds(6));
        activate(
                tenant,
                businessRole.id(),
                List.of(
                        RoleCommandService.MemberSpec.applicationRole(
                                applicationRole.id()),
                        RoleCommandService.MemberSpec.entitlement(
                                secondEntitlement.id())),
                NOW.plusSeconds(7));

        assertThatThrownBy(() -> assignments.createRoleAssignment(
                tenant,
                identity.id(),
                businessRole.id(),
                AccessAssignment.PrincipalConstraintKind.SPECIFIC,
                principal.id(),
                null,
                null,
                NOW.plusSeconds(10)))
                .isInstanceOf(AccessAssignmentCommandException.class)
                .hasMessageContaining("exactly one ApplicationTarget");
    }

    @Test
    void specificRoleStopsContributingWhenCurrentExpansionBecomesMultiTarget() {
        TenantContext tenant = tenant("specific-role-change");
        Identity identity = identity(tenant, "Specific Expansion User");

        var appOne = catalog.createApplication(
                tenant, "specific-one", "Specific One", NOW);
        var targetOne = catalog.createTarget(
                tenant, appOne.id(), "prod", NOW);
        var entitlementOne = catalog.createEntitlement(
                tenant, appOne.id(), targetOne.id(), "read", NOW);
        Role applicationRole = roles.createRole(
                tenant,
                Role.RoleType.APPLICATION,
                appOne.id(),
                "specific-app-role",
                "Specific App Role",
                NOW);
        activate(
                tenant,
                applicationRole.id(),
                List.of(RoleCommandService.MemberSpec.entitlement(
                        entitlementOne.id())),
                NOW.plusSeconds(1));

        Role businessRole = roles.createRole(
                tenant,
                Role.RoleType.BUSINESS,
                null,
                "specific-business-role",
                "Specific Business Role",
                NOW.plusSeconds(4));
        activate(
                tenant,
                businessRole.id(),
                List.of(RoleCommandService.MemberSpec.applicationRole(
                        applicationRole.id())),
                NOW.plusSeconds(5));

        var principal = principals.create(
                tenant,
                targetOne.id(),
                "specific-expansion-user",
                identity.id(),
                NOW.plusSeconds(8),
                ids.nextId(),
                null);

        AccessAssignment assignment = assignments.createRoleAssignment(
                tenant,
                identity.id(),
                businessRole.id(),
                AccessAssignment.PrincipalConstraintKind.SPECIFIC,
                principal.id(),
                null,
                null,
                NOW.plusSeconds(9));
        process(NOW.plusSeconds(10));

        String constraint = "SPECIFIC:" + principal.id();
        assertThat(effectiveQuery.find(
                tenant,
                identity.id(),
                entitlementOne.id(),
                constraint,
                NOW.plusSeconds(10))).isPresent();

        var appTwo = catalog.createApplication(
                tenant, "specific-two", "Specific Two", NOW.plusSeconds(11));
        var targetTwo = catalog.createTarget(
                tenant, appTwo.id(), "prod", NOW.plusSeconds(11));
        var entitlementTwo = catalog.createEntitlement(
                tenant, appTwo.id(), targetTwo.id(), "read", NOW.plusSeconds(11));

        activate(
                tenant,
                businessRole.id(),
                List.of(
                        RoleCommandService.MemberSpec.applicationRole(
                                applicationRole.id()),
                        RoleCommandService.MemberSpec.entitlement(
                                entitlementTwo.id())),
                NOW.plusSeconds(12));

        // Current Catalog meaning is authoritative immediately: stale materialized
        // support for the old single-target expansion cannot preserve SPECIFIC access.
        assertThat(effectiveQuery.find(
                tenant,
                identity.id(),
                entitlementOne.id(),
                constraint,
                NOW.plusSeconds(15))).isEmpty();

        drainCatalogPropagation();
        process(NOW.plusSeconds(16));
        assertThat(effectiveQuery.find(
                tenant,
                identity.id(),
                entitlementOne.id(),
                constraint,
                NOW.plusSeconds(16))).isEmpty();
        assertThat(effectiveQuery.find(
                tenant,
                identity.id(),
                entitlementTwo.id(),
                constraint,
                NOW.plusSeconds(16))).isEmpty();

        assertThat(assignmentRepository.findById(
                tenant, assignment.id())).isPresent();
    }

    @Test
    void roleRefreshPagesNonTerminalAssignmentsAndResumesFromDurableContinuation() {
        TenantContext tenant = tenant("role-refresh-pages");
        Identity identity = identity(tenant, "Paged Role User");
        var app = catalog.createApplication(tenant, "paged", "Paged", NOW);
        var target = catalog.createTarget(tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant, app.id(), target.id(), "read", NOW);
        Role role = roles.createRole(
                tenant, Role.RoleType.APPLICATION, app.id(), "readers", "Readers", NOW);
        activate(tenant, role.id(),
                List.of(RoleCommandService.MemberSpec.entitlement(entitlement.id())),
                NOW.plusSeconds(1));
        drainCatalogPropagation();
        for (int i = 0; i < 10; i++) {
            if (refresh(NOW.plusSeconds(4)).processAvailable().claimed() == 0) break;
        }

        Instant createdAt = NOW.plusSeconds(5);
        for (int i = 0; i < 105; i++) {
            assignmentRepository.insert(tenant, new AccessAssignment(
                    ids.nextId(), identity.id(), AccessAssignment.TargetKind.ROLE,
                    role.id(), null, AccessAssignment.PrincipalConstraintKind.ANY, null,
                    AccessAssignment.ProvenanceKind.MANUAL, null,
                    AccessAssignment.LifecycleState.ACTIVE, null, null, 1,
                    createdAt, createdAt));
        }
        assignmentRepository.insert(tenant, new AccessAssignment(
                ids.nextId(), identity.id(), AccessAssignment.TargetKind.ROLE,
                role.id(), null, AccessAssignment.PrincipalConstraintKind.ANY, null,
                AccessAssignment.ProvenanceKind.MANUAL, null,
                AccessAssignment.LifecycleState.REVOKED, null, null, 1,
                createdAt, createdAt));

        Instant at = NOW.plusSeconds(10);
        new JdbcRoleExpansionFactSink(outbox, ids).changed(
                tenant, role.id(), role.revision(), at, ids.nextId(), null);
        assertThat(refresh(at).processAvailable().processed()).isEqualTo(1);
        assertThat(workCount(tenant, RoleExpansionProjectionRefreshService.ASSIGNMENT_REFRESH_REQUESTED))
                .isEqualTo(100);
        assertThat(workCount(tenant, RoleExpansionProjectionRefreshService.PAGE_REQUESTED))
                .isEqualTo(1);

        // A new processor instance resumes the committed continuation.
        assertThat(refresh(at.plusSeconds(1)).processAvailable().processed()).isEqualTo(1);
        assertThat(workCount(tenant, RoleExpansionProjectionRefreshService.ASSIGNMENT_REFRESH_REQUESTED))
                .isEqualTo(105);
        assertThat(refresh(at.plusSeconds(2)).processAvailable().claimed()).isZero();

        processor(at.plusSeconds(2)).processAvailable();
        processor(at.plusSeconds(2)).processAvailable();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM access.effective_access_support WHERE tenant_id = ?
                """, Integer.class, tenant.tenantId())).isEqualTo(105);
    }

    private int workCount(TenantContext tenant, String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ? AND event_type = ?
                """, Integer.class, tenant.tenantId(), eventType);
    }

    private void process(Instant at) {
        refresh(at).processAvailable();
        processor(at).processAvailable();
    }

    private RoleExpansionProjectionRefreshService refresh(Instant at) {
        return new RoleExpansionProjectionRefreshService(
                outbox, assignmentRepository, ids, transactions,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Clock.fixed(at, ZoneOffset.UTC));
    }

    private void drainCatalogPropagation() {
        for (int i = 0; i < 20; i++) {
            var batch = catalogPropagation.processAvailable();
            if (batch.claimed() == 0) {
                return;
            }
        }
        throw new AssertionError("Catalog propagation did not drain");
    }

    private EffectiveAccessProcessingService processor(Instant at) {
        return new EffectiveAccessProcessingService(
                outbox,
                scheduledWork,
                assignmentRepository,
                effectiveRepository,
                expansion,
                desiredDerivation,
                Clock.fixed(at, ZoneOffset.UTC));
    }

    private io.wyrmgate.iam.catalog.domain.RoleVersion activate(
            TenantContext tenant,
            UUID roleId,
            List<RoleCommandService.MemberSpec> members,
            Instant at) {
        var version = roles.createDraftVersion(
                tenant, roleId, members, at);
        roles.markReady(
                tenant, version.id(), 1, at.plusSeconds(1));
        return roles.activate(
                tenant, version.id(), 2, at.plusSeconds(2));
    }

    private TenantContext tenant(String name) {
        return new TenantContext(
                tenants.createActive(name, NOW).id());
    }

    private Identity identity(
            TenantContext tenant, String displayName) {
        return identities.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                displayName,
                NOW,
                ids.nextId(),
                null);
    }
}
