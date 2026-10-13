package io.wyrmgate.iam.governance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.wyrmgate.iam.governance.application.AccessRequestApprovalResultSink;
import io.wyrmgate.iam.governance.application.AccessRequestCommandService;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityResult;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemSpec;
import io.wyrmgate.iam.governance.application.AccessRequestModels.PrincipalConstraintKind;
import io.wyrmgate.iam.governance.application.AccessRequestModels.TargetKind;
import io.wyrmgate.iam.governance.application.ApprovalCommandService;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import io.wyrmgate.iam.governance.application.RequestsAndApprovalsPageQuery.ApprovalSort;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class RequestsAndApprovalsPageQueryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-14T08:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;
    private static JdbcApprovalRepository approvalRepository;
    private static JdbcAccessRequestRepository requestRepository;
    private static ApprovalCommandService approvals;
    private static JdbcRequestsAndApprovalsPageQuery query;

    private TenantContext tenant;

    @BeforeAll
    static void start() {
        io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.reset();
        var dataSource = io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource));
        approvalRepository = new JdbcApprovalRepository(jdbc);
        requestRepository = new JdbcAccessRequestRepository(jdbc);
        approvals = new ApprovalCommandService(
                approvalRepository,
                new AccessRequestApprovalResultSink(requestRepository, (requestedTenant, item) -> { }),
                ids,
                transactions);
        query = new JdbcRequestsAndApprovalsPageQuery(jdbc);
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
        tenant = new TenantContext(tenants.create("Requests page test", NOW).id());
    }

    @Test
    void projectionKeepsSelfServiceRequestsAndAssignedApprovalWorkActorScoped() {
        UUID requester = ids.nextId();
        UUID approver = ids.nextId();
        UUID entitlementId = ids.nextId();

        AccessRequestCommandService requests = new AccessRequestCommandService(
                requestRepository,
                (requestedTenant, request, item) -> EligibilityResult.approvalRequired(
                        new PlanSpec(List.of(new StageSpec(DecisionMode.ANY_ONE, List.of(approver))))),
                approvals,
                (requestedTenant, authorizedItem) -> { },
                ids,
                transactions);

        var draft = requests.createDraft(
                tenant,
                requester,
                requester,
                List.of(new ItemSpec(
                        TargetKind.ENTITLEMENT,
                        entitlementId,
                        PrincipalConstraintKind.ANY,
                        null,
                        null,
                        null)),
                NOW);

        var draftSnapshot = query.snapshot(
                tenant,
                requester,
                NOW.minusSeconds(3600),
                NOW.plusSeconds(3600),
                ApprovalSort.OLDEST,
                10,
                10);
        assertThat(draftSnapshot.draftRequests()).isEqualTo(1);
        assertThat(draftSnapshot.inFlightRequestItems()).isZero();
        assertThat(draftSnapshot.recentRequests()).singleElement()
                .satisfies(summary -> {
                    assertThat(summary.requestId()).isEqualTo(draft.request().id());
                    assertThat(summary.presentationStatus()).isEqualTo("DRAFT");
                    assertThat(summary.singleTargetKind()).isEqualTo("ENTITLEMENT");
                    assertThat(summary.singleTargetId()).isEqualTo(entitlementId);
                });

        var submitted = requests.submit(
                tenant,
                draft.request().id(),
                draft.request().revision(),
                NOW.plusSeconds(1));
        var pending = requests.evaluateItem(
                tenant,
                submitted.items().getFirst().id(),
                submitted.items().getFirst().revision(),
                NOW.plusSeconds(2));

        var requesterSnapshot = query.snapshot(
                tenant,
                requester,
                NOW.minusSeconds(3600),
                NOW.plusSeconds(3600),
                ApprovalSort.OLDEST,
                10,
                10);
        assertThat(requesterSnapshot.draftRequests()).isZero();
        assertThat(requesterSnapshot.inFlightRequestItems()).isEqualTo(1);
        assertThat(requesterSnapshot.pendingApprovals()).isZero();
        assertThat(requesterSnapshot.recentRequests().getFirst().presentationStatus())
                .isEqualTo("IN_PROGRESS");

        var approverSnapshot = query.snapshot(
                tenant,
                approver,
                NOW.minusSeconds(3600),
                NOW.plusSeconds(3600),
                ApprovalSort.OLDEST,
                10,
                10);
        assertThat(approverSnapshot.pendingApprovals()).isEqualTo(1);
        assertThat(approverSnapshot.draftRequests()).isZero();
        assertThat(approverSnapshot.recentRequests()).isEmpty();
        assertThat(approverSnapshot.approvalWork()).singleElement()
                .satisfies(work -> {
                    assertThat(work.approvalCaseId()).isEqualTo(pending.approvalCaseId());
                    assertThat(work.accessRequestId()).isEqualTo(draft.request().id());
                    assertThat(work.targetKind()).isEqualTo("ENTITLEMENT");
                    assertThat(work.targetId()).isEqualTo(entitlementId);
                });
    }
}
