package io.wyrmgate.iam.governance.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.governance.application.ApprovalActorEligibilityQuery;
import io.wyrmgate.iam.governance.application.ApprovalCommandException;
import io.wyrmgate.iam.governance.application.ApprovalOutcomeSink;
import io.wyrmgate.iam.governance.application.ApprovalService;
import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.governance.domain.ApprovalPlan;
import io.wyrmgate.iam.governance.domain.ApprovalStage;
import io.wyrmgate.iam.governance.domain.ApprovalSubject;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

class ApprovalWorkflowIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-28T08:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcApprovalRepository repository;
    private static JdbcOutboxRepository outbox;
    private static TransactionExecutor transactions;

    private TenantContext tenant;
    private Set<UUID> eligible;
    private ApprovalService service;

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
        assertThat(flyway.info().current().getVersion().getVersion())
                .isEqualTo("26");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        repository = new JdbcApprovalRepository(jdbc);
        outbox = new JdbcOutboxRepository(jdbc);
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
                    governance.approval_decision,
                    governance.approval_participant,
                    governance.approval_stage,
                    governance.approval_plan,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create("Approval Test", NOW).id());
        eligible = new HashSet<>();
        ApprovalActorEligibilityQuery actorEligibility =
                (requestedTenant, identityId) ->
                        requestedTenant.equals(tenant)
                                && eligible.contains(identityId);
        service = new ApprovalService(
                repository,
                new JdbcApprovalOutcomeSink(outbox, ids),
                actorEligibility,
                ids,
                transactions);
    }

    @Test
    void sequentialAllThenAnyOneProducesImmutableDecisionEvidence() {
        UUID approverA = eligible();
        UUID approverB = eligible();
        UUID approverC = eligible();
        UUID approverD = eligible();
        UUID subjectId = ids.nextId();

        ApprovalPlan plan = service.createPlan(
                tenant,
                new ApprovalSubject(
                        ApprovalSubject.Kind.ACCESS_REQUEST_ITEM,
                        subjectId),
                "access-request-policy-v1",
                List.of(
                        new ApprovalService.StageSpec(
                                ApprovalStage.DecisionMode.ALL,
                                List.of(approverA, approverB)),
                        new ApprovalService.StageSpec(
                                ApprovalStage.DecisionMode.ANY_ONE,
                                List.of(approverC, approverD))),
                NOW.plusSeconds(3600),
                NOW);

        assertThat(plan.revision()).isEqualTo(1);
        assertThat(repository.findStages(tenant, plan.id()))
                .extracting(ApprovalStage::lifecycleState)
                .containsExactly(
                        ApprovalStage.LifecycleState.ACTIVE,
                        ApprovalStage.LifecycleState.WAITING);

        ApprovalPlan afterA = service.decide(
                tenant,
                plan.id(),
                approverA,
                ApprovalDecision.Value.APPROVE,
                "approved by A",
                1,
                ids.nextId(),
                null,
                NOW.plusSeconds(1));
        assertThat(afterA.lifecycleState())
                .isEqualTo(ApprovalPlan.LifecycleState.PENDING);
        assertThat(afterA.revision()).isEqualTo(2);
        assertThat(afterA.currentStageOrdinal()).isZero();

        ApprovalPlan afterB = service.decide(
                tenant,
                plan.id(),
                approverB,
                ApprovalDecision.Value.APPROVE,
                null,
                2,
                ids.nextId(),
                null,
                NOW.plusSeconds(2));
        assertThat(afterB.lifecycleState())
                .isEqualTo(ApprovalPlan.LifecycleState.PENDING);
        assertThat(afterB.currentStageOrdinal()).isEqualTo(1);
        assertThat(afterB.revision()).isEqualTo(3);

        ApprovalPlan approved = service.decide(
                tenant,
                plan.id(),
                approverC,
                ApprovalDecision.Value.APPROVE,
                null,
                3,
                ids.nextId(),
                null,
                NOW.plusSeconds(3));
        assertThat(approved.lifecycleState())
                .isEqualTo(ApprovalPlan.LifecycleState.APPROVED);
        assertThat(approved.revision()).isEqualTo(4);

        List<ApprovalStage> stages =
                repository.findStages(tenant, plan.id());
        assertThat(stages)
                .extracting(ApprovalStage::lifecycleState)
                .containsExactly(
                        ApprovalStage.LifecycleState.APPROVED,
                        ApprovalStage.LifecycleState.APPROVED);
        assertThat(repository.findDecisions(
                tenant, stages.get(0).id())).hasSize(2);
        assertThat(repository.findDecisions(
                tenant, stages.get(1).id())).hasSize(1);

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND event_type = ?
                  AND aggregate_id = ?
                """,
                Integer.class,
                tenant.tenantId(),
                ApprovalOutcomeSink.eventType(
                        ApprovalSubject.Kind.ACCESS_REQUEST_ITEM),
                plan.id()))
                .isEqualTo(1);

        UUID decisionId = jdbc.queryForObject("""
                SELECT id
                FROM governance.approval_decision
                WHERE tenant_id = ?
                  AND approval_plan_id = ?
                ORDER BY decided_at, id
                LIMIT 1
                """,
                UUID.class,
                tenant.tenantId(),
                plan.id());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE governance.approval_decision
                SET reason = 'mutated'
                WHERE tenant_id = ? AND id = ?
                """,
                tenant.tenantId(),
                decisionId))
                .hasMessageContaining(
                        "ApprovalDecision is immutable evidence");
    }

    @Test
    void rejectsNonParticipantAndIneligibleParticipant() {
        UUID approver = eligible();
        UUID outsider = eligible();
        ApprovalPlan plan = service.createPlan(
                tenant,
                new ApprovalSubject(
                        ApprovalSubject.Kind.CREDENTIAL_ACTION,
                        ids.nextId()),
                "credential-policy-v1",
                List.of(new ApprovalService.StageSpec(
                        ApprovalStage.DecisionMode.ANY_ONE,
                        List.of(approver))),
                null,
                NOW);

        assertThatThrownBy(() -> service.decide(
                tenant,
                plan.id(),
                outsider,
                ApprovalDecision.Value.APPROVE,
                null,
                1,
                ids.nextId(),
                null,
                NOW.plusSeconds(1)))
                .isInstanceOf(ApprovalCommandException.class)
                .hasMessageContaining(
                        "not an approver");

        eligible.remove(approver);
        assertThatThrownBy(() -> service.decide(
                tenant,
                plan.id(),
                approver,
                ApprovalDecision.Value.APPROVE,
                null,
                1,
                ids.nextId(),
                null,
                NOW.plusSeconds(2)))
                .isInstanceOf(ApprovalCommandException.class)
                .hasMessageContaining(
                        "not currently eligible");
    }

    @Test
    void rejectTerminatesPlanAndSkipsLaterStages() {
        UUID first = eligible();
        UUID later = eligible();
        ApprovalPlan plan = service.createPlan(
                tenant,
                new ApprovalSubject(
                        ApprovalSubject.Kind.GOVERNANCE_EXCEPTION,
                        ids.nextId()),
                "exception-policy-v1",
                List.of(
                        new ApprovalService.StageSpec(
                                ApprovalStage.DecisionMode.ANY_ONE,
                                List.of(first)),
                        new ApprovalService.StageSpec(
                                ApprovalStage.DecisionMode.ANY_ONE,
                                List.of(later))),
                null,
                NOW);

        ApprovalPlan rejected = service.decide(
                tenant,
                plan.id(),
                first,
                ApprovalDecision.Value.REJECT,
                "insufficient justification",
                1,
                ids.nextId(),
                null,
                NOW.plusSeconds(1));

        assertThat(rejected.lifecycleState())
                .isEqualTo(ApprovalPlan.LifecycleState.REJECTED);
        assertThat(repository.findStages(tenant, plan.id()))
                .extracting(ApprovalStage::lifecycleState)
                .containsExactly(
                        ApprovalStage.LifecycleState.REJECTED,
                        ApprovalStage.LifecycleState.SKIPPED);

        assertThatThrownBy(() -> service.decide(
                tenant,
                plan.id(),
                first,
                ApprovalDecision.Value.APPROVE,
                null,
                rejected.revision(),
                ids.nextId(),
                null,
                NOW.plusSeconds(2)))
                .isInstanceOf(ApprovalCommandException.class)
                .hasMessageContaining(
                        "no longer accepts decisions");
    }

    private UUID eligible() {
        UUID id = ids.nextId();
        eligible.add(id);
        return id;
    }
}
