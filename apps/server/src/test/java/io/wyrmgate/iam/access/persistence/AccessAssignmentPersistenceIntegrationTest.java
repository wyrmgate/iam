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
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class AccessAssignmentPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-25T06:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static TenantTestFixture tenants;
    private static TransactionExecutor transactions;
    private static IdentityCommandService identities;
    private static PrincipalCommandService principals;
    private static CatalogCommandService catalogCommands;
    private static CatalogQueryService catalogQuery;
    private static JdbcAccessAssignmentRepository assignmentRepository;
    private static AccessAssignmentCommandService assignments;

    @BeforeAll
    static void start() {
        io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.reset();
        var dataSource = io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new TenantTestFixture(jdbc, ids);
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
        catalogCommands = new CatalogCommandService(
                catalogRepository,
                io.wyrmgate.iam.testsupport.CatalogAccessTestSupport.propagationStarter(
                        jdbc, ids, transactions),
                ids,
                transactions);
        catalogQuery = new CatalogQueryService(catalogRepository);

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
                catalogQuery,
                noPrincipalFacts,
                ids,
                transactions,
                (tenant, principalId, identityId, at) -> {});

        assignmentRepository = new JdbcAccessAssignmentRepository(jdbc);
        var roleRepository =
                new io.wyrmgate.iam.catalog.persistence.JdbcRoleRepository(jdbc);
        var roleExpansion =
                new io.wyrmgate.iam.catalog.application.RoleExpansionQueryService(
                        catalogRepository, roleRepository);
        var catalogAvailability =
                io.wyrmgate.iam.testsupport.CatalogAccessTestSupport.availability(
                        catalogRepository, roleRepository);
        assignments = new AccessAssignmentCommandService(
                assignmentRepository,
                new IdentityAccessReferenceQueryService(
                        identityRepository, principalRepository),
                catalogQuery,
                catalogAvailability,
                roleExpansion,
                (tenant, assignment) -> { },
                (tenant, assignment, now) -> { },
                ids,
                transactions);
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
                    tenancy.tenant
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
    void dueScheduledAccessCanBeReducedWithoutWaitingForBoundaryWork() {
        TenantContext tenant = tenant("scheduled-reduction");
        Identity identity = identity(tenant, "Scheduled User");
        var catalog = catalog(tenant, "scheduled-access", "prod");
        Instant starts = NOW.plusSeconds(60);
        Instant ends = starts.plusSeconds(60);

        AccessAssignment scheduled = assignments.createEntitlementAssignment(
                tenant, identity.id(), catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY, null, starts, ends, NOW);

        assertThatThrownBy(() -> assignments.revoke(
                tenant, scheduled.id(), scheduled.revision(), NOW))
                .isInstanceOf(AccessAssignmentCommandException.class);
        assertThatThrownBy(() -> assignments.suspend(
                tenant, scheduled.id(), scheduled.revision(), NOW))
                .isInstanceOf(AccessAssignmentCommandException.class);
        assertThatThrownBy(() -> assignments.cancel(
                tenant, scheduled.id(), scheduled.revision(), starts))
                .isInstanceOf(AccessAssignmentCommandException.class);

        assertThat(assignmentRepository.findById(tenant, scheduled.id()).orElseThrow()
                .lifecycleState()).isEqualTo(AccessAssignment.LifecycleState.SCHEDULED);
        AccessAssignment suspended = assignments.suspend(
                tenant, scheduled.id(), scheduled.revision(), starts);
        assertThat(suspended.lifecycleState()).isEqualTo(AccessAssignment.LifecycleState.SUSPENDED);
        assertThat(suspended.isSemanticallyEffectiveAt(starts)).isFalse();

        AccessAssignment resumed = assignments.resume(
                tenant, suspended.id(), suspended.revision(), starts.plusSeconds(1));
        assertThat(resumed.lifecycleState()).isEqualTo(AccessAssignment.LifecycleState.ACTIVE);
        assertThat(resumed.isSemanticallyEffectiveAt(starts.plusSeconds(1))).isTrue();

        AccessAssignment another = assignments.createEntitlementAssignment(
                tenant, identity.id(), catalog.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY, null, starts, ends, NOW);
        AccessAssignment revoked = assignments.revoke(
                tenant, another.id(), another.revision(), starts);
        assertThat(revoked.lifecycleState()).isEqualTo(AccessAssignment.LifecycleState.REVOKED);
        assertThat(revoked.isSemanticallyEffectiveAt(starts)).isFalse();
        assertThatThrownBy(() -> assignments.resume(
                tenant, revoked.id(), revoked.revision(), starts.plusSeconds(1)))
                .isInstanceOf(AccessAssignmentCommandException.class);
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
    void rejectsMissingIdentityRetiredTargetAndEndedValidity() {
        TenantContext tenant = tenant("invalid");
        Identity identity = identity(tenant, "Invalid");
        var missingIdentityTarget = catalog(tenant, "missing-identity", "prod");

        assertThatThrownBy(() -> assignments.createEntitlementAssignment(
                tenant,
                ids.nextId(),
                missingIdentityTarget.entitlement().id(),
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                null,
                null,
                NOW))
                .isInstanceOf(AccessAssignmentCommandException.class)
                .hasMessageContaining("Identity was not found");

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
                .hasMessageContaining("not currently assignable");

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
        return new TenantContext(tenants.createActive(name, NOW).id());
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
                NOW);
        return new CatalogFixture(application, target, entitlement);
    }

    private record CatalogFixture(
            io.wyrmgate.iam.catalog.domain.Application application,
            io.wyrmgate.iam.catalog.domain.ApplicationTarget target,
            io.wyrmgate.iam.catalog.domain.Entitlement entitlement) {
    }
}
