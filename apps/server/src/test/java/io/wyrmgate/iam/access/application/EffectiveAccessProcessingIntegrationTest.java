package io.wyrmgate.iam.access.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentBoundaryScheduler;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentFactSink;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentRepository;
import io.wyrmgate.iam.access.persistence.JdbcEffectiveAccessRepository;
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
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class EffectiveAccessProcessingIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-09-25T07:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;
    private static IdentityCommandService identities;
    private static PrincipalCommandService principals;
    private static CatalogCommandService catalogCommands;
    private static CatalogQueryService catalogQuery;
    private static JdbcAccessAssignmentRepository assignmentRepository;
    private static JdbcEffectiveAccessRepository effectiveRepository;
    private static JdbcOutboxRepository outbox;
    private static JdbcScheduledWorkRepository scheduledWork;
    private static AccessAssignmentCommandService assignments;
    private static EffectiveAccessQuery effectiveQuery;

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
                    TenantContext tenant, Identity identity,
                    java.util.UUID correlationId, java.util.UUID causationId) {
            }

            @Override
            public void displayNameChanged(
                    TenantContext tenant, Identity identity,
                    java.util.UUID correlationId, java.util.UUID causationId) {
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

        outbox = new JdbcOutboxRepository(jdbc);
        scheduledWork = new JdbcScheduledWorkRepository(jdbc, ids);
        assignmentRepository = new JdbcAccessAssignmentRepository(jdbc);
        effectiveRepository = new JdbcEffectiveAccessRepository(jdbc, ids);

        assignments = new AccessAssignmentCommandService(
                assignmentRepository,
                new IdentityAccessReferenceQueryService(
                        identityRepository, principalRepository),
                catalogQuery,
                new JdbcAccessAssignmentFactSink(outbox, ids),
                new JdbcAccessAssignmentBoundaryScheduler(scheduledWork),
                ids,
                transactions);
        effectiveQuery = new EffectiveAccessQueryService(effectiveRepository);
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
                    platform.scheduled_work,
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
    }

    @Test
    void directAssignmentsAreIdempotentAndSupportCounted() {
        TenantContext tenant = tenant("support");
        Identity identity = identity(tenant, "Support User");
        var catalog = catalog(tenant, "support-app");

        AccessAssignment first = assignments.createEntitlementAssignment(
                tenant, identity.id(), catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null, null, null, NOW);
        processor(NOW).processAvailable();

        var initial = effectiveQuery.find(
                tenant, identity.id(), catalog.entitlement().id(), "ANY", NOW)
                .orElseThrow();
        assertThat(initial.effectiveAccess().supportCount()).isEqualTo(1);
        assertThat(initial.effectiveAccess().projectionGeneration()).isEqualTo(1);

        processor(NOW.plusSeconds(1)).reconcile(
                tenant, first.id(), NOW.plusSeconds(1));
        var replayed = effectiveQuery.find(
                tenant, identity.id(), catalog.entitlement().id(), "ANY",
                NOW.plusSeconds(1)).orElseThrow();
        assertThat(replayed.effectiveAccess().projectionGeneration()).isEqualTo(1);

        AccessAssignment second = assignments.createEntitlementAssignment(
                tenant, identity.id(), catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null, null, null, NOW.plusSeconds(2));
        processor(NOW.plusSeconds(2)).processAvailable();

        var doubled = effectiveQuery.find(
                tenant, identity.id(), catalog.entitlement().id(), "ANY",
                NOW.plusSeconds(2)).orElseThrow();
        assertThat(doubled.effectiveAccess().supportCount()).isEqualTo(2);
        assertThat(doubled.supportingAssignmentIds())
                .containsExactlyInAnyOrder(first.id(), second.id());

        assignments.terminate(
                tenant, first.id(), first.revision(), NOW.plusSeconds(3));
        processor(NOW.plusSeconds(3)).processAvailable();
        assertThat(effectiveQuery.find(
                tenant, identity.id(), catalog.entitlement().id(), "ANY",
                NOW.plusSeconds(3)).orElseThrow()
                .effectiveAccess().supportCount()).isEqualTo(1);

        assignments.terminate(
                tenant, second.id(), second.revision(), NOW.plusSeconds(4));
        processor(NOW.plusSeconds(4)).processAvailable();
        assertThat(effectiveQuery.find(
                tenant, identity.id(), catalog.entitlement().id(), "ANY",
                NOW.plusSeconds(4))).isEmpty();
    }

    @Test
    void validityBoundariesActivateAndExpireWithoutAssignmentStateRewrite() {
        TenantContext tenant = tenant("time");
        Identity identity = identity(tenant, "Time User");
        var catalog = catalog(tenant, "time-app");
        Instant starts = NOW.plusSeconds(60);
        Instant ends = NOW.plusSeconds(120);

        AccessAssignment scheduled = assignments.createEntitlementAssignment(
                tenant, identity.id(), catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null, starts, ends, NOW);

        processor(NOW).processAvailable();
        assertThat(effectiveQuery.find(
                tenant, identity.id(), catalog.entitlement().id(), "ANY", NOW))
                .isEmpty();

        processor(starts).processAvailable();
        assertThat(effectiveQuery.find(
                tenant, identity.id(), catalog.entitlement().id(), "ANY", starts))
                .isPresent();
        assertThat(assignmentRepository.findById(tenant, scheduled.id())
                .orElseThrow().lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.SCHEDULED);

        processor(ends).processAvailable();
        assertThat(effectiveQuery.find(
                tenant, identity.id(), catalog.entitlement().id(), "ANY", ends))
                .isEmpty();
        assertThat(assignmentRepository.findById(tenant, scheduled.id())
                .orElseThrow().lifecycleState())
                .isEqualTo(AccessAssignment.LifecycleState.SCHEDULED);
    }

    @Test
    void specificPrincipalProducesSeparateEffectiveTupleAndTenantIsolation() {
        TenantContext tenant = tenant("specific");
        Identity identity = identity(tenant, "Specific User");
        var catalog = catalog(tenant, "specific-app");
        var principal = principals.create(
                tenant,
                catalog.target().id(),
                "specific-user",
                identity.id(),
                NOW,
                ids.nextId(),
                null);

        assignments.createEntitlementAssignment(
                tenant, identity.id(), catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null, null, null, NOW);
        assignments.createEntitlementAssignment(
                tenant, identity.id(), catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.SPECIFIC,
                principal.id(), null, null, NOW);
        processor(NOW).processAvailable();

        assertThat(effectiveQuery.find(
                tenant, identity.id(), catalog.entitlement().id(), "ANY", NOW))
                .isPresent();
        assertThat(effectiveQuery.find(
                tenant,
                identity.id(),
                catalog.entitlement().id(),
                "SPECIFIC:" + principal.id(),
                NOW)).isPresent();

        TenantContext other = tenant("other");
        assertThat(effectiveQuery.find(
                other, identity.id(), catalog.entitlement().id(), "ANY", NOW))
                .isEmpty();
    }

    private EffectiveAccessProcessingService processor(Instant at) {
        return new EffectiveAccessProcessingService(
                outbox,
                scheduledWork,
                assignmentRepository,
                effectiveRepository,
                Clock.fixed(at, ZoneOffset.UTC));
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

    private CatalogFixture catalog(TenantContext tenant, String code) {
        var application = catalogCommands.createApplication(
                tenant, code, code, NOW);
        var target = catalogCommands.createTarget(
                tenant, application.id(), "prod", NOW);
        var entitlement = catalogCommands.createEntitlement(
                tenant,
                application.id(),
                target.id(),
                "standard",
                "standard",
                "GROUP",
                NOW);
        return new CatalogFixture(target, entitlement);
    }

    private record CatalogFixture(
            io.wyrmgate.iam.catalog.domain.ApplicationTarget target,
            io.wyrmgate.iam.catalog.domain.Entitlement entitlement) {
    }
}
