package io.wyrmgate.iam.access.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.access.application.AccessAssignmentCommandException;
import io.wyrmgate.iam.access.application.AccessAssignmentCommandService;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogRepository;
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
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class AccessAssignmentPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-25T06:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;
    private static IdentityCommandService identities;
    private static PrincipalCommandService principals;
    private static CatalogCommandService catalogCommands;
    private static CatalogQueryService catalogQuery;
    private static JdbcAccessAssignmentRepository assignmentRepository;
    private static AccessAssignmentCommandService assignments;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("20");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));

        var identityRepository = new JdbcIdentityRepository(jdbc);
        IdentityFactSink noIdentityFacts = new IdentityFactSink() {
            @Override
            public void identityCreated(
                    TenantContext tenant,
                    Identity identity,
                    java.util.UUID correlationId,
                    java.util.UUID causationId) {
            }

            @Override
            public void displayNameChanged(
                    TenantContext tenant,
                    Identity identity,
                    java.util.UUID correlationId,
                    java.util.UUID causationId) {
            }
        };
        identities = new IdentityCommandService(
                identityRepository, noIdentityFacts, ids, transactions);

        var catalogRepository = new JdbcCatalogRepository(jdbc);
        catalogCommands = new CatalogCommandService(
                catalogRepository, ids, transactions);
        catalogQuery = new CatalogQueryService(catalogRepository);

        var principalRepository = new JdbcPrincipalRepository(jdbc);
        PrincipalFactSink noPrincipalFacts =
                (tenant, principal, correlationId, causationId) -> { };
        principals = new PrincipalCommandService(
                principalRepository,
                identityRepository,
                catalogQuery,
                noPrincipalFacts,
                ids,
                transactions);

        assignmentRepository = new JdbcAccessAssignmentRepository(jdbc);
        assignments = new AccessAssignmentCommandService(
                assignmentRepository,
                new IdentityAccessReferenceQueryService(
                        identityRepository, principalRepository),
                catalogQuery,
                (tenant, assignment) -> { },
                (tenant, assignment, now) -> { },
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
                    access.effective_access_support,
                    access.effective_access,
                    access.access_assignment,
                    access.desired_grant_state,
                    access.desired_principal_state,
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
    }

    @Test
    void createsActiveEntitlementAssignmentAndEvaluatesValidityFromClock() {
        TenantContext tenant = tenant("active");
        Identity identity = identity(tenant, "Ada");
        var catalog = catalog(tenant, "payroll", "prod");

        AccessAssignment assignment = assignments.createEntitlementAssignment(
                tenant,
                identity.id(),
                catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                null,
                NOW.plusSeconds(3600),
                NOW);

        assertThat(assignment.lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.ACTIVE);
        assertThat(assignment.revision()).isEqualTo(1);
        assertThat(assignment.targetKind())
                .isEqualTo(AccessAssignment.TargetKind.ENTITLEMENT);
        assertThat(assignment.provenanceKind())
                .isEqualTo(AccessAssignment.ProvenanceKind.MANUAL);
        assertThat(assignment.isSemanticallyEffectiveAt(NOW)).isTrue();
        assertThat(assignment.isSemanticallyEffectiveAt(NOW.plusSeconds(3600)))
                .isFalse();

        TenantContext other = tenant("other");
        assertThat(assignmentRepository.findById(other, assignment.id())).isEmpty();
    }

    @Test
    void scheduledAssignmentBecomesSemanticallyEffectiveByTimeAndCanBeCancelledBeforeStart() {
        TenantContext tenant = tenant("scheduled");
        Identity identity = identity(tenant, "Future User");
        var catalog = catalog(tenant, "erp", "prod");
        Instant validFrom = NOW.plusSeconds(3600);

        AccessAssignment scheduled = assignments.createEntitlementAssignment(
                tenant,
                identity.id(),
                catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                validFrom,
                validFrom.plusSeconds(3600),
                NOW);

        assertThat(scheduled.lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.SCHEDULED);
        assertThat(scheduled.isSemanticallyEffectiveAt(NOW)).isFalse();
        assertThat(scheduled.isSemanticallyEffectiveAt(validFrom)).isTrue();

        AccessAssignment cancelled = assignments.terminate(
                tenant, scheduled.id(), scheduled.revision(), NOW.plusSeconds(60));
        assertThat(cancelled.lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.CANCELLED);
        assertThat(cancelled.revision()).isEqualTo(2);
        assertThat(cancelled.isSemanticallyEffectiveAt(validFrom)).isFalse();
    }

    @Test
    void terminationMaterializesRevokedOrExpiredAndUsesOptimisticRevision() {
        TenantContext tenant = tenant("terminate");
        Identity identity = identity(tenant, "Term User");
        var catalog = catalog(tenant, "finance", "prod");

        AccessAssignment active = assignments.createEntitlementAssignment(
                tenant,
                identity.id(),
                catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                null,
                NOW.plusSeconds(3600),
                NOW);
        AccessAssignment revoked = assignments.terminate(
                tenant, active.id(), active.revision(), NOW.plusSeconds(10));
        assertThat(revoked.lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.REVOKED);

        assertThatThrownBy(() -> assignments.terminate(
                tenant, active.id(), active.revision(), NOW.plusSeconds(20)))
                .isInstanceOf(StaleWriteException.class);

        AccessAssignment expiring = assignments.createEntitlementAssignment(
                tenant,
                identity.id(),
                catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                null,
                NOW.plusSeconds(30),
                NOW);
        AccessAssignment expired = assignments.terminate(
                tenant,
                expiring.id(),
                expiring.revision(),
                NOW.plusSeconds(31));
        assertThat(expired.lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.EXPIRED);
    }

    @Test
    void specificPrincipalMustResolveToSameIdentityAndTarget() {
        TenantContext tenant = tenant("specific");
        Identity identity = identity(tenant, "Owner");
        Identity otherIdentity = identity(tenant, "Other");
        var catalog = catalog(tenant, "crm", "prod");
        var otherCatalog = catalog(tenant, "crm2", "prod");

        var matching = principals.create(
                tenant,
                catalog.target().id(),
                "owner-account",
                identity.id(),
                NOW,
                ids.nextId(),
                null);
        var wrongIdentity = principals.create(
                tenant,
                catalog.target().id(),
                "other-account",
                otherIdentity.id(),
                NOW,
                ids.nextId(),
                null);
        var wrongTarget = principals.create(
                tenant,
                otherCatalog.target().id(),
                "other-target",
                identity.id(),
                NOW,
                ids.nextId(),
                null);
        var uncorrelated = principals.create(
                tenant,
                catalog.target().id(),
                "unmatched",
                null,
                NOW,
                ids.nextId(),
                null);

        AccessAssignment specific = assignments.createEntitlementAssignment(
                tenant,
                identity.id(),
                catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.SPECIFIC,
                matching.id(),
                null,
                null,
                NOW);
        assertThat(specific.specificPrincipalId()).isEqualTo(matching.id());

        assertThatThrownBy(() -> assignments.createEntitlementAssignment(
                tenant,
                identity.id(),
                catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.SPECIFIC,
                wrongIdentity.id(),
                null,
                null,
                NOW))
                .isInstanceOf(AccessAssignmentCommandException.class)
                .hasMessageContaining("another Identity");

        assertThatThrownBy(() -> assignments.createEntitlementAssignment(
                tenant,
                identity.id(),
                catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.SPECIFIC,
                wrongTarget.id(),
                null,
                null,
                NOW))
                .isInstanceOf(AccessAssignmentCommandException.class)
                .hasMessageContaining("another ApplicationTarget");

        assertThatThrownBy(() -> assignments.createEntitlementAssignment(
                tenant,
                identity.id(),
                catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.SPECIFIC,
                uncorrelated.id(),
                null,
                null,
                NOW))
                .isInstanceOf(AccessAssignmentCommandException.class)
                .hasMessageContaining("not correlated");
    }

    @Test
    void rejectsMissingIdentityUntargetedEntitlementRetiredTargetAndEndedValidity() {
        TenantContext tenant = tenant("invalid");
        Identity identity = identity(tenant, "Invalid");
        var application = catalogCommands.createApplication(
                tenant, "untargeted", "untargeted", NOW);
        var untargeted = catalogCommands.createEntitlement(
                tenant,
                application.id(),
                null,
                "global",
                "global",
                "GROUP",
                NOW);

        assertThatThrownBy(() -> assignments.createEntitlementAssignment(
                tenant,
                ids.nextId(),
                untargeted.id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                null,
                null,
                NOW))
                .isInstanceOf(AccessAssignmentCommandException.class)
                .hasMessageContaining("Identity was not found");

        assertThatThrownBy(() -> assignments.createEntitlementAssignment(
                tenant,
                identity.id(),
                untargeted.id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                null,
                null,
                NOW))
                .isInstanceOf(AccessAssignmentCommandException.class)
                .hasMessageContaining("target-scoped");

        var targeted = catalog(tenant, "retired-target", "prod");
        catalogCommands.retireTarget(
                tenant, targeted.target().id(), targeted.target().revision(),
                NOW.plusSeconds(1));

        assertThatThrownBy(() -> assignments.createEntitlementAssignment(
                tenant,
                identity.id(),
                targeted.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                null,
                null,
                NOW.plusSeconds(2)))
                .isInstanceOf(AccessAssignmentCommandException.class)
                .hasMessageContaining("retired ApplicationTarget");

        var active = catalog(tenant, "validity", "prod");
        assertThatThrownBy(() -> assignments.createEntitlementAssignment(
                tenant,
                identity.id(),
                active.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                NOW,
                NOW,
                NOW))
                .isInstanceOf(AccessAssignmentCommandException.class)
                .hasMessageContaining("already be ended");
    }

    private TenantContext tenant(String name) {
        return new TenantContext(tenants.create(name, NOW).id());
    }

    private Identity identity(TenantContext tenant, String displayName) {
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

    private CatalogFixture catalog(
            TenantContext tenant, String applicationCode, String targetCode) {
        var application = catalogCommands.createApplication(
                tenant, applicationCode, applicationCode, NOW);
        var target = catalogCommands.createTarget(
                tenant, application.id(), targetCode, NOW);
        var entitlement = catalogCommands.createEntitlement(
                tenant,
                application.id(),
                target.id(),
                "standard",
                "standard",
                "GROUP",
                NOW);
        return new CatalogFixture(application, target, entitlement);
    }

    private record CatalogFixture(
            io.wyrmgate.iam.catalog.domain.Application application,
            io.wyrmgate.iam.catalog.domain.ApplicationTarget target,
            io.wyrmgate.iam.catalog.domain.Entitlement entitlement) {
    }
}
