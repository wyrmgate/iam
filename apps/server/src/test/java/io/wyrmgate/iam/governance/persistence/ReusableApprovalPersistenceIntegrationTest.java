package io.wyrmgate.iam.governance.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.governance.application.ApprovalCommandException;
import io.wyrmgate.iam.governance.application.ApprovalPlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.governance.application.ApprovalRepository;
import io.wyrmgate.iam.governance.application.ApprovalService;
import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
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

class ReusableApprovalPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-28T04:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static ApprovalRepository repository;
    private static ApprovalService approvals;
    private static ApprovalQueryService queries;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
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
        TransactionExecutor transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        approvals = new ApprovalService(
                repository,
                new JdbcApprovalOutcomeFactSink(
                        new JdbcOutboxRepository(jdbc), ids),
                ids,
                transactions);
        queries = new ApprovalQueryService(repository);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clear() {
        jdbc.execute("""
                TRUNCATE TABLE
                    governance.approval_decision,
                    governance.approval_participant,
                    governance.approval_stage,
                    governance.approval_plan,
                    governance.approval_case,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);
    }

    @Test
    void sequentialAnyOneAndAllStagesAreReusableAcrossTypedSubjects() {
        TenantContext tenant = tenant("approval");
        UUID requester = ids.nextId();
        UUID a = ids.nextId();
        UUID b = ids.nextId();
        UUID c = ids.nextId();
        UUID d = ids.nextId();
        UUID subjectId = ids.nextId();

        ApprovalCase opened = approvals.openCase(
                tenant,
                ApprovalCase.SubjectType.CREDENTIAL_OPERATION,
                subjectId,
                7,
                requester,
                new ApprovalPlanSpec(
                        ApprovalPlanSpec.SelfApprovalPolicy.DENY_REQUESTER,
                        List.of(
                                new ApprovalPlanSpec.StageSpec(
                                        ApprovalPlanSpec.DecisionMode.ANY_ONE,
                                        List.of(a, b)),
                                new ApprovalPlanSpec.StageSpec(
                                        ApprovalPlanSpec.DecisionMode.ALL,
                                        List.of(c, d)))),
                NOW,
                ids.nextId(),
                null);

        assertThat(queries.inbox(tenant, a, null, 10).items())
                .hasSize(1);
        assertThat(queries.inbox(tenant, c, null, 10).items())
                .isEmpty();

        ApprovalCase afterA = approvals.decide(
                tenant,
                opened.id(),
                a,
                ApprovalDecision.Decision.APPROVE,
                1,
                NOW.plusSeconds(1),
                ids.nextId(),
                null);
        assertThat(afterA.lifecycleState())
                .isEqualTo(ApprovalCase.LifecycleState.PENDING);
        assertThat(afterA.currentStageOrdinal()).isEqualTo(1);
        assertThat(afterA.revision()).isEqualTo(2);

        assertThat(queries.inbox(tenant, b, null, 10).items())
                .isEmpty();
        assertThat(queries.inbox(tenant, c, null, 10).items())
                .hasSize(1);
        assertThat(queries.inbox(tenant, d, null, 10).items())
                .hasSize(1);

        ApprovalCase afterC = approvals.decide(
                tenant,
                opened.id(),
                c,
                ApprovalDecision.Decision.APPROVE,
                2,
                NOW.plusSeconds(2),
                ids.nextId(),
                null);
        assertThat(afterC.lifecycleState())
                .isEqualTo(ApprovalCase.LifecycleState.PENDING);
        assertThat(afterC.currentStageOrdinal()).isEqualTo(1);
        assertThat(afterC.revision()).isEqualTo(3);
        assertThat(queries.inbox(tenant, c, null, 10).items())
                .isEmpty();
        assertThat(queries.inbox(tenant, d, null, 10).items())
                .hasSize(1);

        ApprovalCase approved = approvals.decide(
                tenant,
                opened.id(),
                d,
                ApprovalDecision.Decision.APPROVE,
                3,
                NOW.plusSeconds(3),
                ids.nextId(),
                null);
        assertThat(approved.lifecycleState())
                .isEqualTo(ApprovalCase.LifecycleState.APPROVED);
        assertThat(approved.revision()).isEqualTo(4);
        assertThat(approved.completedAt()).isNotNull();

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM governance.approval_decision
                WHERE tenant_id = ? AND approval_case_id = ?
                """, Integer.class, tenant.tenantId(), opened.id()))
                .isEqualTo(3);

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND event_type =
                    'governance.approval-outcome.credential-operation'
                  AND aggregate_id = ?
                """, Integer.class, tenant.tenantId(), opened.id()))
                .isEqualTo(1);

        UUID planId = jdbc.queryForObject("""
                SELECT id
                FROM governance.approval_plan
                WHERE tenant_id = ? AND approval_case_id = ?
                """, UUID.class, tenant.tenantId(), opened.id());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE governance.approval_plan
                SET self_approval_policy = 'ALLOW_REQUESTER'
                WHERE tenant_id = ? AND id = ?
                """, tenant.tenantId(), planId))
                .hasMessageContaining("immutable");

        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM governance.approval_decision
                WHERE tenant_id = ? AND approval_case_id = ?
                """, tenant.tenantId(), opened.id()))
                .hasMessageContaining("immutable");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE governance.approval_case
                SET subject_revision = subject_revision + 1
                WHERE tenant_id = ? AND id = ?
                """, tenant.tenantId(), opened.id()))
                .hasMessageContaining("immutable");
    }

    @Test
    void rejectionIsTerminalAndOnlyCurrentResolvedParticipantCanDecide() {
        TenantContext tenant = tenant("reject");
        UUID requester = ids.nextId();
        UUID approver = ids.nextId();
        UUID stranger = ids.nextId();

        ApprovalCase opened = approvals.openCase(
                tenant,
                ApprovalCase.SubjectType.GOVERNANCE_EXCEPTION,
                ids.nextId(),
                2,
                requester,
                oneStage(approver),
                NOW,
                ids.nextId(),
                null);

        assertThatThrownBy(() -> approvals.decide(
                tenant,
                opened.id(),
                stranger,
                ApprovalDecision.Decision.APPROVE,
                1,
                NOW.plusSeconds(1),
                ids.nextId(),
                null))
                .isInstanceOf(ApprovalCommandException.class)
                .hasMessageContaining("not a participant");

        ApprovalCase rejected = approvals.decide(
                tenant,
                opened.id(),
                approver,
                ApprovalDecision.Decision.REJECT,
                1,
                NOW.plusSeconds(2),
                ids.nextId(),
                null);
        assertThat(rejected.lifecycleState())
                .isEqualTo(ApprovalCase.LifecycleState.REJECTED);

        assertThatThrownBy(() -> approvals.decide(
                tenant,
                opened.id(),
                approver,
                ApprovalDecision.Decision.APPROVE,
                rejected.revision(),
                NOW.plusSeconds(3),
                ids.nextId(),
                null))
                .isInstanceOf(ApprovalCommandException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void denyRequesterPlanCannotResolveRequesterAsApprover() {
        TenantContext tenant = tenant("self");
        UUID requester = ids.nextId();

        assertThatThrownBy(() -> approvals.openCase(
                tenant,
                ApprovalCase.SubjectType.ADMINISTRATIVE_ELEVATION,
                ids.nextId(),
                1,
                requester,
                oneStage(requester),
                NOW,
                ids.nextId(),
                null))
                .isInstanceOf(ApprovalCommandException.class)
                .hasMessageContaining("requester");
    }

    private ApprovalPlanSpec oneStage(UUID approver) {
        return new ApprovalPlanSpec(
                ApprovalPlanSpec.SelfApprovalPolicy.DENY_REQUESTER,
                List.of(new ApprovalPlanSpec.StageSpec(
                        ApprovalPlanSpec.DecisionMode.ANY_ONE,
                        List.of(approver))));
    }

    private TenantContext tenant(String name) {
        return new TenantContext(
                tenants.create(name, NOW).id());
    }
}
