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
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
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

class RoleEffectiveAccessIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-25T12:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;
    private static IdentityCommandService identities;
    private static PrincipalCommandService principals;
    private static CatalogCommandService catalog;
    private static RoleCommandService roles;
    private static RoleExpansionQueryService expansion;
    private static JdbcOutboxRepository outbox;
    private static JdbcScheduledWorkRepository scheduledWork;
    private static JdbcAccessAssignmentRepository assignmentRepository;
    private static JdbcEffectiveAccessRepository effectiveRepository;
    private static JdbcDesiredStateProjectionRepository desiredRepository;
    private static AccessAssignmentCommandService assignments;
    private static DesiredStateDerivationService desiredDerivation;
    private static EffectiveAccessQuery effectiveQuery;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion())
                .isEqualTo("25");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
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
        };
        identities = new IdentityCommandService(
                identityRepository, noIdentityFacts, ids, transactions);

        var catalogRepository = new JdbcCatalogRepository(jdbc);
        var roleRepository = new JdbcRoleRepository(jdbc);
        catalog = new CatalogCommandService(
                catalogRepository, ids, transactions);
        roles = new RoleCommandService(
                catalogRepository,
                roleRepository,
                new JdbcRoleExpansionFactSink(outbox, ids),
                ids,
                transactions);
        expansion = new RoleExpansionQueryService(
                catalogRepository, roleRepository);

        var principalRepository = new JdbcPrincipalRepository(jdbc);
        PrincipalFactSink noPrincipalFacts =
                (tenant, principal, correlationId, causationId) -> { };
        principals = new PrincipalCommandService(
                principalRepository,
                identityRepository,
                new io.wyrmgate.iam.catalog.application.CatalogQueryService(
                        catalogRepository),
                noPrincipalFacts,
                ids,
                transactions);
        var identityReferences = new IdentityAccessReferenceQueryService(
                identityRepository, principalRepository);

        assignmentRepository = new JdbcAccessAssignmentRepository(jdbc);
        effectiveRepository = new JdbcEffectiveAccessRepository(jdbc, ids);
        desiredRepository = new JdbcDesiredStateProjectionRepository(jdbc, ids);
        effectiveQuery = new EffectiveAccessQueryService(effectiveRepository);
        desiredDerivation = new DesiredStateDerivationService(
                effectiveQuery,
                desiredRepository,
                new io.wyrmgate.iam.catalog.application.CatalogQueryService(
                        catalogRepository),
                identityReferences,
                (tenant, state) -> { },
                transactions);
        assignments = new AccessAssignmentCommandService(
                assignmentRepository,
                identityReferences,
                new io.wyrmgate.iam.catalog.application.CatalogQueryService(
                        catalogRepository),
                expansion,
                new JdbcAccessAssignmentFactSink(outbox, ids),
                new JdbcAccessAssignmentBoundaryScheduler(scheduledWork),
                ids,
                transactions);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
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
                    platform.tenant
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
        var e1 = catalog.createEntitlement(
                tenant, appOne.id(), targetOne.id(),
                "one-read", "one-read", "GROUP", NOW);
        var e1Next = catalog.createEntitlement(
                tenant, appOne.id(), targetOne.id(),
                "one-write", "one-write", "GROUP", NOW);

        var appTwo = catalog.createApplication(
                tenant, "two", "Two", NOW);
        var targetTwo = catalog.createTarget(
                tenant, appTwo.id(), "prod", NOW);
        var e2 = catalog.createEntitlement(
                tenant, appTwo.id(), targetTwo.id(),
                "two-read", "two-read", "GROUP", NOW);

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

        processor(NOW.plusSeconds(9)).processAvailable();

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

        processor(NOW.plusSeconds(13)).processAvailable();

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
        processor(NOW.plusSeconds(14)).processAvailable();

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
        var entitlement = catalog.createEntitlement(
                tenant, app.id(), target.id(),
                "read", "read", "GROUP", NOW);
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
        var secondEntitlement = catalog.createEntitlement(
                tenant, secondApp.id(), secondTarget.id(),
                "read", "read", "GROUP", NOW);
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
                tenants.create(name, NOW).id());
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
