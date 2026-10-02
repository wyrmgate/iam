package io.wyrmgate.iam.access.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.IdentityAccessReduction;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentFactSink;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentRepository;
import io.wyrmgate.iam.access.persistence.JdbcEffectiveAccessRepository;
import io.wyrmgate.iam.access.persistence.JdbcIdentityAccessReductionRepository;
import io.wyrmgate.iam.access.persistence.JdbcIdentityAccessReductionWorkSink;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQueryService;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityRepository;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

class IdentityAccessReductionIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-29T10:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;

    private TenantContext tenant;
    private JdbcOutboxRepository outbox;
    private IdentityRepository identityRepository;
    private IdentityCommandService identities;
    private IdentityAccessReferenceQueryService identityReferences;
    private JdbcAccessAssignmentRepository assignments;
    private AccessAssignmentCommandService assignmentCommands;
    private JdbcIdentityAccessReductionRepository reductions;
    private JdbcIdentityAccessReductionWorkSink reductionWork;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current()
                .getVersion().getVersion())
                .isEqualTo("52");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE
                    access.identity_access_reduction,
                    access.effective_access_support,
                    access.effective_access,
                    access.access_assignment,
                    platform.scheduled_work,
                    platform.outbox_event,
                    identity.principal,
                    identity.person_profile,
                    identity.service_profile,
                    identity.workload_profile,
                    identity.identity,
                    platform.tenant
                CASCADE
                """);

        tenant = new TenantContext(
                tenants.create("Identity reduction test", NOW).id());
        outbox = new JdbcOutboxRepository(jdbc);
        identityRepository = new JdbcIdentityRepository(jdbc);
        identities = new IdentityCommandService(
                identityRepository,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);
        identityReferences =
                new IdentityAccessReferenceQueryService(
                        identityRepository,
                        new JdbcPrincipalRepository(jdbc));
        assignments =
                new JdbcAccessAssignmentRepository(jdbc);
        assignmentCommands =
                new AccessAssignmentCommandService(
                        assignments,
                        identityReferences,
                        (requestedTenant, entitlementId) ->
                                io.wyrmgate.iam.catalog.application
                                        .CatalogAccessReferenceQuery
                                        .EntitlementReference.notFound(),
                        new JdbcAccessAssignmentFactSink(
                                outbox, ids),
                        (requestedTenant, assignment, now) -> { },
                        ids,
                        transactions);
        reductions =
                new JdbcIdentityAccessReductionRepository(
                        jdbc);
        reductionWork =
                new JdbcIdentityAccessReductionWorkSink(
                        outbox, ids);
    }

    @Test
    void lifecycleLossHidesEffectiveAccessBeforeAsyncReductionAndBlocksNewGrant() {
        Identity identity = activeIdentity(
                "Immediate Leaver");
        UUID entitlementId = ids.nextId();
        AccessAssignment assignment =
                assignment(
                        identity.id(),
                        entitlementId,
                        AccessAssignment.LifecycleState.ACTIVE,
                        NOW.minusSeconds(10),
                        null,
                        null);
        assignments.insert(tenant, assignment);

        var effective =
                new JdbcEffectiveAccessRepository(
                        jdbc, ids);
        effective.applyDirectAssignment(
                tenant,
                assignment,
                "ANY",
                "direct",
                NOW.minusSeconds(5));
        var query = new EffectiveAccessQueryService(
                effective, identityReferences);

        assertThat(query.find(
                tenant,
                identity.id(),
                entitlementId,
                "ANY",
                NOW)).isPresent();

        Identity inactive = identities.changeLifecycle(
                tenant,
                identity.id(),
                IdentityLifecycleState.INACTIVE,
                identity.revision(),
                NOW.plusSeconds(1),
                ids.nextId(),
                null);

        assertThat(assignments.findById(
                tenant, assignment.id()).orElseThrow()
                .lifecycleState())
                .isEqualTo(
                        AccessAssignment.LifecycleState.ACTIVE);
        assertThat(query.find(
                tenant,
                identity.id(),
                entitlementId,
                "ANY",
                NOW.plusSeconds(1))).isEmpty();

        assertThatThrownBy(() ->
                assignmentCommands.createEntitlementAssignment(
                        tenant,
                        inactive.id(),
                        ids.nextId(),
                        AccessAssignment.PrincipalConstraintKind.ANY,
                        null,
                        null,
                        null,
                        NOW.plusSeconds(1)))
                .isInstanceOf(
                        AccessAssignmentCommandException.class)
                .hasMessageContaining("not access-eligible");
    }

    @Test
    void reductionPagesBeyondTwoHundredAndResumesAfterProcessorRestart() {
        Identity identity = activeIdentity(
                "Paged Leaver");
        for (int i = 0; i < 205; i++) {
            assignments.insert(
                    tenant,
                    assignment(
                            identity.id(),
                            ids.nextId(),
                            AccessAssignment.LifecycleState.ACTIVE,
                            NOW.minusSeconds(10)
                                    .plusNanos(i),
                            null,
                            null));
        }

        Identity inactive = identities.changeLifecycle(
                tenant,
                identity.id(),
                IdentityLifecycleState.INACTIVE,
                identity.revision(),
                NOW,
                ids.nextId(),
                null);

        intake(NOW.plusSeconds(1))
                .processAvailable();

        IdentityAccessReduction reduction =
                reductions.findBySource(
                                tenant,
                                identity.id(),
                                inactive.revision())
                        .orElseThrow();
        assertThat(reduction.state())
                .isEqualTo(
                        IdentityAccessReduction.State.RUNNING);

        processor(NOW.plusSeconds(2))
                .processAvailable();

        IdentityAccessReduction firstPage =
                reductions.findById(
                                tenant,
                                reduction.id())
                        .orElseThrow();
        assertThat(firstPage.state())
                .isEqualTo(
                        IdentityAccessReduction.State.RUNNING);
        assertThat(firstPage.processedAssignmentCount())
                .isEqualTo(200);
        assertThat(nonTerminalCount(identity.id()))
                .isEqualTo(5);

        processor(NOW.plusSeconds(3))
                .processAvailable();

        IdentityAccessReduction completed =
                reductions.findById(
                                tenant,
                                reduction.id())
                        .orElseThrow();
        assertThat(completed.state())
                .isEqualTo(
                        IdentityAccessReduction.State.COMPLETED);
        assertThat(completed.processedAssignmentCount())
                .isEqualTo(205);
        assertThat(nonTerminalCount(identity.id()))
                .isZero();
        assertThat(assignmentStateCount(
                identity.id(), "REVOKED"))
                .isEqualTo(205);
    }

    @Test
    void reductionMapsFutureExpiredAndCurrentAssignmentsToSemanticTerminalStates() {
        Identity identity = activeIdentity(
                "Mapped Leaver");
        AccessAssignment current =
                assignment(
                        identity.id(),
                        ids.nextId(),
                        AccessAssignment.LifecycleState.ACTIVE,
                        NOW.minusSeconds(5),
                        null,
                        null);
        AccessAssignment suspended =
                assignment(
                        identity.id(),
                        ids.nextId(),
                        AccessAssignment.LifecycleState.SUSPENDED,
                        NOW.minusSeconds(4),
                        null,
                        null);
        AccessAssignment expired =
                assignment(
                        identity.id(),
                        ids.nextId(),
                        AccessAssignment.LifecycleState.ACTIVE,
                        NOW.minusSeconds(20),
                        NOW.minusSeconds(10),
                        NOW.minusSeconds(1));
        AccessAssignment scheduled =
                assignment(
                        identity.id(),
                        ids.nextId(),
                        AccessAssignment.LifecycleState.SCHEDULED,
                        NOW.minusSeconds(3),
                        NOW.plusSeconds(100),
                        null);
        AccessAssignment alreadyTerminal =
                assignment(
                        identity.id(),
                        ids.nextId(),
                        AccessAssignment.LifecycleState.REVOKED,
                        NOW.minusSeconds(2),
                        null,
                        null);
        assignments.insert(tenant, current);
        assignments.insert(tenant, suspended);
        assignments.insert(tenant, expired);
        assignments.insert(tenant, scheduled);
        assignments.insert(tenant, alreadyTerminal);

        identities.changeLifecycle(
                tenant,
                identity.id(),
                IdentityLifecycleState.DECOMMISSIONED,
                identity.revision(),
                NOW,
                ids.nextId(),
                null);
        intake(NOW.plusSeconds(1)).processAvailable();
        processor(NOW.plusSeconds(2)).processAvailable();

        assertState(
                current.id(),
                AccessAssignment.LifecycleState.REVOKED);
        assertState(
                suspended.id(),
                AccessAssignment.LifecycleState.REVOKED);
        assertState(
                expired.id(),
                AccessAssignment.LifecycleState.EXPIRED);
        assertState(
                scheduled.id(),
                AccessAssignment.LifecycleState.CANCELLED);
        assertState(
                alreadyTerminal.id(),
                AccessAssignment.LifecycleState.REVOKED);
    }

    @Test
    void duplicateFactIsCausallyIdempotentAndReactivationStopsStaleContinuation() {
        Identity identity = activeIdentity(
                "Reactivated User");
        for (int i = 0; i < 205; i++) {
            assignments.insert(
                    tenant,
                    assignment(
                            identity.id(),
                            ids.nextId(),
                            AccessAssignment.LifecycleState.ACTIVE,
                            NOW.minusSeconds(10)
                                    .plusNanos(i),
                            null,
                            null));
        }

        Identity inactive = identities.changeLifecycle(
                tenant,
                identity.id(),
                IdentityLifecycleState.INACTIVE,
                identity.revision(),
                NOW,
                ids.nextId(),
                null);
        intake(NOW.plusSeconds(1)).processAvailable();

        UUID duplicateId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        duplicateId,
                        IdentityAccessReductionIntakeService
                                .ACCESS_ELIGIBILITY_CHANGED,
                        1,
                        "identity",
                        identity.id(),
                        inactive.revision(),
                        NOW,
                        duplicateId,
                        null,
                        """
                        {"previousLifecycleState":"ACTIVE",
                         "lifecycleState":"INACTIVE",
                         "accessEligible":false}
                        """),
                NOW.plusSeconds(1));
        intake(NOW.plusSeconds(2)).processAvailable();

        Integer reductionCount = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM access.identity_access_reduction
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND source_identity_revision = ?
                """,
                Integer.class,
                tenant.tenantId(),
                identity.id(),
                inactive.revision());
        assertThat(reductionCount).isEqualTo(1);

        processor(NOW.plusSeconds(3))
                .processAvailable();
        assertThat(nonTerminalCount(identity.id()))
                .isEqualTo(5);

        Identity reactivated =
                identities.changeLifecycle(
                        tenant,
                        identity.id(),
                        IdentityLifecycleState.ACTIVE,
                        inactive.revision(),
                        NOW.plusSeconds(4),
                        ids.nextId(),
                        null);
        assertThat(reactivated.lifecycleState())
                .isEqualTo(
                        IdentityLifecycleState.ACTIVE);

        processor(NOW.plusSeconds(5))
                .processAvailable();

        IdentityAccessReduction completed =
                reductions.findBySource(
                                tenant,
                                identity.id(),
                                inactive.revision())
                        .orElseThrow();
        assertThat(completed.state())
                .isEqualTo(
                        IdentityAccessReduction.State.COMPLETED);
        assertThat(completed.processedAssignmentCount())
                .isEqualTo(200);
        assertThat(nonTerminalCount(identity.id()))
                .isEqualTo(5);
    }

    private IdentityAccessReductionIntakeService intake(
            Instant at) {
        return new IdentityAccessReductionIntakeService(
                outbox,
                reductions,
                reductionWork,
                ids,
                transactions,
                new ObjectMapper(),
                Clock.fixed(at, ZoneOffset.UTC));
    }

    private IdentityAccessReductionProcessingService processor(
            Instant at) {
        return new IdentityAccessReductionProcessingService(
                outbox,
                reductions,
                assignments,
                assignmentCommands,
                identityReferences,
                reductionWork,
                transactions,
                Clock.fixed(at, ZoneOffset.UTC));
    }

    private Identity activeIdentity(String name) {
        return identities.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                name,
                NOW.minusSeconds(30),
                ids.nextId(),
                null);
    }

    private AccessAssignment assignment(
            UUID identityId,
            UUID entitlementId,
            AccessAssignment.LifecycleState state,
            Instant createdAt,
            Instant validFrom,
            Instant validUntil) {
        return new AccessAssignment(
                ids.nextId(),
                identityId,
                AccessAssignment.TargetKind.ENTITLEMENT,
                null,
                entitlementId,
                AccessAssignment.PrincipalConstraintKind.ANY,
                null,
                AccessAssignment.ProvenanceKind.MANUAL,
                null,
                state,
                validFrom,
                validUntil,
                1,
                createdAt,
                createdAt);
    }

    private long nonTerminalCount(UUID identityId) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM access.access_assignment
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND lifecycle_state IN (
                      'ACTIVE','SUSPENDED','SCHEDULED')
                """,
                Long.class,
                tenant.tenantId(),
                identityId);
        return count == null ? 0 : count;
    }

    private long assignmentStateCount(
            UUID identityId,
            String state) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM access.access_assignment
                WHERE tenant_id = ?
                  AND identity_id = ?
                  AND lifecycle_state = ?
                """,
                Long.class,
                tenant.tenantId(),
                identityId,
                state);
        return count == null ? 0 : count;
    }

    private void assertState(
            UUID assignmentId,
            AccessAssignment.LifecycleState state) {
        assertThat(assignments.findById(
                tenant, assignmentId).orElseThrow()
                .lifecycleState())
                .isEqualTo(state);
    }
}
