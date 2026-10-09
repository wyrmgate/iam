package io.wyrmgate.iam.governance.persistence;

import static io.wyrmgate.iam.platform.persistence.FlywayTestSupport.assertFullyMigrated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.governance.application.AccessRequestApprovalResultSink;
import io.wyrmgate.iam.governance.application.AccessRequestCommandService;
import io.wyrmgate.iam.governance.application.AccessRequestEligibilityEvaluator;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityResult;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemSpec;
import io.wyrmgate.iam.governance.application.AccessRequestModels.PrincipalConstraintKind;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemState;
import io.wyrmgate.iam.governance.application.AccessRequestModels.TargetKind;
import io.wyrmgate.iam.governance.application.ApprovalCommandException;
import io.wyrmgate.iam.governance.application.ApprovalCommandService;
import io.wyrmgate.iam.governance.application.ApprovalModels.CaseState;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionValue;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.SubjectKind;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
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

class ApprovalRequestPersistenceIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-28T08:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;
    private static JdbcApprovalRepository approvalRepository;
    private static JdbcAccessRequestRepository requestRepository;
    private static ApprovalCommandService approvals;

    private TenantContext tenant;

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
        assertFullyMigrated(flyway);

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        approvalRepository =
                new JdbcApprovalRepository(jdbc);
        requestRepository =
                new JdbcAccessRequestRepository(jdbc);
        approvals = new ApprovalCommandService(
                approvalRepository,
                new AccessRequestApprovalResultSink(
                        requestRepository,
                        (requestedTenant, item) -> { }),
                ids,
                transactions);
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
                    governance.approval_approver,
                    governance.approval_stage,
                    governance.approval_plan,
                    governance.request_item,
                    governance.access_request,
                    governance.approval_case,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create("Approval test", NOW).id());
    }

    @Test
    void reusableApprovalSupportsSequentialAllAndAnyOneOutsideAccessRequest() {
        UUID initiator = ids.nextId();
        UUID first = ids.nextId();
        UUID second = ids.nextId();
        UUID finalApprover = ids.nextId();
        UUID subjectId = ids.nextId();

        var approvalCase = approvals.start(
                tenant,
                SubjectKind.GOVERNANCE_EXCEPTION,
                subjectId,
                initiator,
                new PlanSpec(List.of(
                        new StageSpec(
                                DecisionMode.ALL,
                                List.of(first, second)),
                        new StageSpec(
                                DecisionMode.ANY_ONE,
                                List.of(finalApprover)))),
                NOW);

        var afterFirst = approvals.decide(
                tenant,
                approvalCase.id(),
                first,
                DecisionValue.APPROVE,
                "first approval",
                1,
                NOW.plusSeconds(1));
        assertThat(afterFirst.state())
                .isEqualTo(CaseState.PENDING);
        assertThat(afterFirst.currentStageOrdinal())
                .isZero();
        assertThat(afterFirst.revision()).isEqualTo(2);

        var replay = approvals.decide(
                tenant,
                approvalCase.id(),
                first,
                DecisionValue.APPROVE,
                "retry",
                1,
                NOW.plusSeconds(2));
        assertThat(replay.revision()).isEqualTo(2);

        var nextStage = approvals.decide(
                tenant,
                approvalCase.id(),
                second,
                DecisionValue.APPROVE,
                null,
                2,
                NOW.plusSeconds(3));
        assertThat(nextStage.currentStageOrdinal())
                .isEqualTo(1);
        assertThat(nextStage.revision()).isEqualTo(3);

        var rejected = approvals.decide(
                tenant,
                approvalCase.id(),
                finalApprover,
                DecisionValue.REJECT,
                "security rejected",
                3,
                NOW.plusSeconds(4));
        assertThat(rejected.state())
                .isEqualTo(CaseState.REJECTED);
        assertThat(rejected.completedAt()).isNotNull();

        TenantContext otherTenant = new TenantContext(
                tenants.create("Other tenant", NOW).id());
        assertThat(approvalRepository.findCase(
                otherTenant, approvalCase.id()))
                .isEmpty();

        UUID planId = approvalRepository
                .findPlan(tenant, approvalCase.id())
                .id();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE governance.approval_plan
                SET content_hash = 'changed'
                WHERE tenant_id = ? AND id = ?
                """, tenant.tenantId(), planId))
                .hasMessageContaining(
                        "approval plan/stage/approver/decision evidence is immutable");
    }

    @Test
    void accessRequestConsumesReusableApprovalAndBecomesAuthorized() {
        UUID requester = ids.nextId();
        UUID beneficiary = ids.nextId();
        UUID approver = ids.nextId();
        UUID entitlementId = ids.nextId();

        AccessRequestEligibilityEvaluator evaluator =
                (requestedTenant, request, item) ->
                        EligibilityResult.approvalRequired(
                                new PlanSpec(List.of(
                                        new StageSpec(
                                                DecisionMode.ANY_ONE,
                                                List.of(approver)))));
        AccessRequestCommandService requests =
                new AccessRequestCommandService(
                        requestRepository,
                        evaluator,
                        approvals,
                        (requestedTenant, authorizedItem) -> { },
                        ids,
                        transactions);

        var draft = requests.createDraft(
                tenant,
                requester,
                beneficiary,
                List.of(new ItemSpec(
                        TargetKind.ENTITLEMENT,
                        entitlementId,
                        PrincipalConstraintKind.ANY,
                        null,
                        null,
                        null)),
                NOW);
        var submitted = requests.submit(
                tenant,
                draft.request().id(),
                1,
                NOW.plusSeconds(1));
        var item = submitted.items().getFirst();
        assertThat(item.state())
                .isEqualTo(ItemState.SUBMITTED);
        assertThat(item.revision()).isEqualTo(2);

        var pending = requests.evaluateItem(
                tenant,
                item.id(),
                2,
                NOW.plusSeconds(2));
        assertThat(pending.state())
                .isEqualTo(ItemState.PENDING_APPROVAL);
        assertThat(pending.approvalCaseId()).isNotNull();

        var approved = approvals.decide(
                tenant,
                pending.approvalCaseId(),
                approver,
                DecisionValue.APPROVE,
                null,
                1,
                NOW.plusSeconds(3));
        assertThat(approved.state())
                .isEqualTo(CaseState.APPROVED);

        var after = requestRepository
                .findItem(tenant, item.id())
                .orElseThrow();
        assertThat(after.state())
                .isEqualTo(ItemState.AUTHORIZED);
        assertThat(after.evaluationCode())
                .isEqualTo("approved");
    }

    @Test
    void mandatoryEvaluatorFailureRemainsPendingAndSelfApprovalFailsClosed() {
        UUID requester = ids.nextId();
        UUID beneficiary = ids.nextId();
        UUID roleId = ids.nextId();

        AccessRequestCommandService unavailableRequests =
                new AccessRequestCommandService(
                        requestRepository,
                        (requestedTenant, request, item) -> {
                            throw new IllegalStateException(
                                    "policy service unavailable");
                        },
                        approvals,
                        (requestedTenant, authorizedItem) -> { },
                        ids,
                        transactions);

        var draft = unavailableRequests.createDraft(
                tenant,
                requester,
                beneficiary,
                List.of(new ItemSpec(
                        TargetKind.ROLE,
                        roleId,
                        PrincipalConstraintKind.ANY,
                        null,
                        null,
                        null)),
                NOW);
        var submitted = unavailableRequests.submit(
                tenant,
                draft.request().id(),
                1,
                NOW.plusSeconds(1));
        var item = submitted.items().getFirst();

        var evaluating =
                unavailableRequests.evaluateItem(
                        tenant,
                        item.id(),
                        2,
                        NOW.plusSeconds(2));
        assertThat(evaluating.state())
                .isEqualTo(ItemState.EVALUATING);
        assertThat(evaluating.evaluationCode())
                .isEqualTo(
                        "mandatory_evaluator_unavailable");

        var selfCase = approvals.start(
                tenant,
                SubjectKind.GOVERNANCE_EXCEPTION,
                ids.nextId(),
                requester,
                new PlanSpec(List.of(
                        new StageSpec(
                                DecisionMode.ANY_ONE,
                                List.of(requester)))),
                NOW.plusSeconds(3));

        assertThatThrownBy(() -> approvals.decide(
                tenant,
                selfCase.id(),
                requester,
                DecisionValue.APPROVE,
                null,
                1,
                NOW.plusSeconds(4)))
                .isInstanceOf(
                        ApprovalCommandException.class)
                .hasMessageContaining(
                        "Self-approval");
        assertThat(approvalRepository
                .findCase(tenant, selfCase.id())
                .orElseThrow()
                .state())
                .isEqualTo(CaseState.PENDING);
    }
}
