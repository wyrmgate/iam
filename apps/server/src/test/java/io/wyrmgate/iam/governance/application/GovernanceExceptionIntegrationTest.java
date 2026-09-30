package io.wyrmgate.iam.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.governance.application.AccessRequestModels.*;
import io.wyrmgate.iam.governance.application.ApprovalModels.*;
import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.*;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.*;
import io.wyrmgate.iam.governance.persistence.*;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class GovernanceExceptionIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-29T05:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;

    private TenantContext tenant;
    private JdbcGovernancePolicyRepository policies;
    private GovernancePolicyService policyService;
    private JdbcApprovalRepository approvalRepository;
    private ApprovalCaseStartService starter;
    private JdbcGovernanceExceptionRepository exceptionRepository;
    private JdbcOutboxRepository outbox;
    private JdbcScheduledWorkRepository scheduledWork;
    private GovernanceExceptionService exceptionService;
    private ApprovalCommandService approvalCommands;
    private JdbcAccessRequestRepository requests;
    private GovernancePolicyEligibilityEvaluator evaluator;

    private final Set<UUID> activeEntitlements =
            new HashSet<>();
    private final Set<UUID> currentEffective =
            new HashSet<>();

    private final CatalogAccessReferenceQuery catalog =
            (requestedTenant, entitlementId) ->
                    activeEntitlements.contains(entitlementId)
                            ? CatalogAccessReferenceQuery
                                    .EntitlementReference.valid(
                                            UUID.nameUUIDFromBytes(
                                                    ("target-" + entitlementId)
                                                            .getBytes()))
                            : CatalogAccessReferenceQuery
                                    .EntitlementReference.notFound();

    private final RoleExpansionQuery roles =
            (requestedTenant, roleId) ->
                    RoleExpansionQuery.Result.unavailable(
                            RoleExpansionQuery.Status.NOT_FOUND,
                            roleId);

    private final EffectiveAccessQuery effectiveAccess =
            new EffectiveAccessQuery() {
                @Override
                public Optional<Result> find(
                        TenantContext requestedTenant,
                        UUID identityId,
                        UUID entitlementId,
                        String principalConstraintKey,
                        Instant at) {
                    return Optional.empty();
                }

                @Override
                public Set<UUID> currentEntitlementIds(
                        TenantContext requestedTenant,
                        UUID identityId,
                        Set<UUID> entitlementIds,
                        Instant at) {
                    Set<UUID> result =
                            new HashSet<>(entitlementIds);
                    result.retainAll(currentEffective);
                    return Set.copyOf(result);
                }
            };

    private final IdentityAccessReferenceQuery identities =
            new IdentityAccessReferenceQuery() {
                @Override
                public boolean identityExists(
                        TenantContext requestedTenant,
                        UUID identityId) {
                    return true;
                }

                @Override
                public PrincipalReference principal(
                        TenantContext requestedTenant,
                        UUID principalId) {
                    return PrincipalReference.notFound();
                }

                @Override
                public PrincipalSelection selectUniqueActivePrincipal(
                        TenantContext requestedTenant,
                        UUID identityId,
                        UUID applicationTargetId) {
                    return PrincipalSelection.none();
                }
            };

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
                .isEqualTo("37");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(
                        dataSource));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE
                    governance.policy_evaluation,
                    governance.sod_conflict,
                    governance.risk_assessment,
                    governance.governance_exception,
                    governance.policy_approval_approver,
                    governance.policy_approval_stage,
                    governance.sod_rule,
                    governance.policy_version,
                    governance.policy,
                    governance.approval_decision,
                    governance.approval_approver,
                    governance.approval_stage,
                    governance.approval_plan,
                    governance.request_item,
                    governance.access_request,
                    governance.approval_case,
                    platform.scheduled_work,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);

        tenant = new TenantContext(
                tenants.create(
                        "Governance exception test", NOW).id());
        policies = new JdbcGovernancePolicyRepository(
                jdbc);
        policyService = new GovernancePolicyService(
                policies,
                catalog,
                identities,
                ids,
                transactions);
        approvalRepository =
                new JdbcApprovalRepository(jdbc);
        starter = new ApprovalCaseStartService(
                approvalRepository,
                ids,
                transactions);
        exceptionRepository =
                new JdbcGovernanceExceptionRepository(
                        jdbc);
        outbox = new JdbcOutboxRepository(jdbc);
        scheduledWork =
                new JdbcScheduledWorkRepository(
                        jdbc, ids);
        exceptionService =
                new GovernanceExceptionService(
                        exceptionRepository,
                        policies,
                        identities,
                        starter,
                        new JdbcGovernanceExceptionChangeSink(
                                outbox, ids),
                        new JdbcGovernanceExceptionBoundaryScheduler(
                                scheduledWork),
                        ids,
                        transactions);
        requests = new JdbcAccessRequestRepository(jdbc);
        evaluator =
                new GovernancePolicyEligibilityEvaluator(
                        policyService,
                        policies,
                        requests,
                        catalog,
                        roles,
                        effectiveAccess,
                        exceptionService,
                        ids,
                        transactions,
                        Clock.fixed(
                                NOW.plusSeconds(30),
                                ZoneOffset.UTC));
        SubmittedRequestItemSink submitted =
                new SubmittedRequestItemSink() {
                    @Override
                    public void submitted(
                            TenantContext requestedTenant,
                            RequestItem item) {}

                    @Override
                    public void retryEvaluation(
                            TenantContext requestedTenant,
                            RequestItem item) {}
                };
        AccessRequestApprovalResultSink requestResults =
                new AccessRequestApprovalResultSink(
                        requests,
                        (requestedTenant, item) -> { },
                        evaluator,
                        approvalRepository,
                        starter,
                        submitted);
        approvalCommands =
                new ApprovalCommandService(
                        approvalRepository,
                        new CompositeApprovalResultSink(
                                List.of(
                                        requestResults,
                                        new GovernanceExceptionApprovalResultSink(
                                                exceptionService))),
                        ids,
                        transactions);

        activeEntitlements.clear();
        currentEffective.clear();
    }

    @Test
    void approvedExceptionWaivesRuleActionButRetainsConflictEvidence() {
        UUID held = entitlement();
        UUID requested = entitlement();
        UUID subject = ids.nextId();
        UUID requester = ids.nextId();
        UUID approver = ids.nextId();
        currentEffective.add(held);

        SoDRule rule = activateDenyRule(
                "toxic-pair",
                held,
                requested,
                NOW);

        GovernanceException exception =
                exceptionService.request(
                        tenant,
                        subject,
                        rule.id(),
                        requester,
                        "Temporary compensating control",
                        NOW,
                        NOW.plusSeconds(3600),
                        null,
                        plan(approver),
                        NOW.plusSeconds(1));
        assertThat(exception.lifecycleState())
                .isEqualTo(
                        LifecycleState.PENDING_APPROVAL);
        assertThat(exceptionService.effectiveExceptionIds(
                tenant,
                subject,
                Set.of(rule.id()),
                NOW.plusSeconds(2)))
                .isEmpty();

        assertThatThrownBy(() -> approvalCommands.decide(
                tenant,
                exception.approvalCaseId(),
                requester,
                DecisionValue.APPROVE,
                null,
                1,
                NOW.plusSeconds(2)))
                .isInstanceOf(ApprovalCommandException.class);

        approvalCommands.decide(
                tenant,
                exception.approvalCaseId(),
                approver,
                DecisionValue.APPROVE,
                "approved exception",
                1,
                NOW.plusSeconds(3));

        GovernanceException approved =
                exceptionService.find(
                        tenant, exception.id())
                        .orElseThrow();
        assertThat(approved.lifecycleState())
                .isEqualTo(LifecycleState.APPROVED);
        assertThat(approved.effectiveAt(
                NOW.plusSeconds(30)))
                .isTrue();

        RequestItem authorized =
                evaluateRequest(
                        subject,
                        requested,
                        NOW.plusSeconds(4));
        assertThat(authorized.state())
                .isEqualTo(ItemState.AUTHORIZED);
        assertThat(authorized.evaluationCode())
                .isEqualTo(
                        "sod_exception_authorized");

        Map<String,Object> evidence =
                jdbc.queryForMap("""
                        SELECT r.severity,
                               c.enforcement_action,
                               c.governance_exception_id
                        FROM governance.sod_conflict c
                        JOIN governance.risk_assessment r
                          ON r.tenant_id = c.tenant_id
                         AND r.id = c.risk_assessment_id
                        WHERE c.tenant_id = ?
                          AND r.request_item_id = ?
                        """,
                        tenant.tenantId(),
                        authorized.id());
        assertThat(evidence.get("severity"))
                .isEqualTo("CRITICAL");
        assertThat(evidence.get(
                "enforcement_action"))
                .isEqualTo("DENY");
        assertThat(evidence.get(
                "governance_exception_id"))
                .isEqualTo(exception.id());

        GovernanceException revoked =
                exceptionService.revoke(
                        tenant,
                        exception.id(),
                        approved.revision(),
                        NOW.plusSeconds(40));
        assertThat(revoked.lifecycleState())
                .isEqualTo(LifecycleState.REVOKED);
        assertThat(exceptionService.effectiveExceptionIds(
                tenant,
                subject,
                Set.of(rule.id()),
                NOW.plusSeconds(41)))
                .isEmpty();

        RequestItem denied =
                evaluateRequest(
                        subject,
                        requested,
                        NOW.plusSeconds(42));
        assertThat(denied.state())
                .isEqualTo(ItemState.DENIED);
        assertThat(denied.evaluationCode())
                .isEqualTo("sod_denied");
    }

    @Test
    void renewalCreatesNonOverlappingSuccessorAndOldRuleDoesNotCarryToNewPolicy() {
        UUID held = entitlement();
        UUID requested = entitlement();
        UUID subject = ids.nextId();
        UUID requester = ids.nextId();
        UUID approver = ids.nextId();

        SoDRule firstRule = activateDenyRule(
                "same-human-code",
                held,
                requested,
                NOW);
        GovernanceException predecessor =
                approveException(
                        subject,
                        firstRule.id(),
                        requester,
                        approver,
                        NOW,
                        NOW.plusSeconds(100),
                        null);

        assertThatThrownBy(() ->
                exceptionService.request(
                        tenant,
                        subject,
                        firstRule.id(),
                        requester,
                        "overlap",
                        NOW.plusSeconds(99),
                        NOW.plusSeconds(200),
                        predecessor.id(),
                        plan(approver),
                        NOW.plusSeconds(10)))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "must not overlap");

        GovernanceException successor =
                exceptionService.request(
                        tenant,
                        subject,
                        firstRule.id(),
                        requester,
                        "renewal",
                        NOW.plusSeconds(100),
                        NOW.plusSeconds(200),
                        predecessor.id(),
                        plan(approver),
                        NOW.plusSeconds(10));
        assertThat(successor.predecessorExceptionId())
                .isEqualTo(predecessor.id());
        assertThat(exceptionService.find(
                        tenant, predecessor.id())
                        .orElseThrow().validUntil())
                .isEqualTo(NOW.plusSeconds(100));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE governance.governance_exception
                SET valid_until = ?
                WHERE tenant_id = ? AND id = ?
                """,
                java.sql.Timestamp.from(
                        NOW.plusSeconds(500)),
                tenant.tenantId(),
                predecessor.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(
                        "renewal creates a successor");

        SoDRule replacementRule =
                activateDenyRule(
                        "same-human-code",
                        held,
                        requested,
                        NOW.plusSeconds(20));
        assertThat(replacementRule.id())
                .isNotEqualTo(firstRule.id());

        currentEffective.add(held);
        RequestItem denied =
                evaluateRequest(
                        subject,
                        requested,
                        NOW.plusSeconds(30));
        assertThat(denied.state())
                .isEqualTo(ItemState.DENIED);

        assertThatThrownBy(() ->
                exceptionService.request(
                        tenant,
                        subject,
                        firstRule.id(),
                        requester,
                        "old rule",
                        NOW.plusSeconds(30),
                        NOW.plusSeconds(60),
                        null,
                        plan(approver),
                        NOW.plusSeconds(30)))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "active SoD rule");
    }

    @Test
    void rejectionSelfApprovalAndFutureValidityNeverGrantPrematureCoverage() {
        UUID left = entitlement();
        UUID right = entitlement();
        UUID subject = ids.nextId();
        UUID requester = ids.nextId();
        UUID approver = ids.nextId();
        SoDRule rule = activateDenyRule(
                "approval-semantics",
                left,
                right,
                NOW);

        GovernanceException selfApproval =
                exceptionService.request(
                        tenant,
                        subject,
                        rule.id(),
                        requester,
                        "self approval forbidden",
                        NOW,
                        NOW.plusSeconds(100),
                        null,
                        plan(requester),
                        NOW.plusSeconds(1));
        assertThatThrownBy(() -> approvalCommands.decide(
                tenant,
                selfApproval.approvalCaseId(),
                requester,
                DecisionValue.APPROVE,
                null,
                1,
                NOW.plusSeconds(2)))
                .isInstanceOf(ApprovalCommandException.class)
                .hasMessageContaining("Self-approval");

        GovernanceException rejected =
                exceptionService.request(
                        tenant,
                        subject,
                        rule.id(),
                        requester,
                        "rejected exception",
                        NOW,
                        NOW.plusSeconds(100),
                        null,
                        plan(approver),
                        NOW.plusSeconds(3));
        approvalCommands.decide(
                tenant,
                rejected.approvalCaseId(),
                approver,
                DecisionValue.REJECT,
                "not acceptable",
                1,
                NOW.plusSeconds(4));
        assertThat(exceptionService.find(
                tenant, rejected.id()).orElseThrow()
                .lifecycleState())
                .isEqualTo(LifecycleState.REJECTED);

        GovernanceException future =
                exceptionService.request(
                        tenant,
                        subject,
                        rule.id(),
                        requester,
                        "future exception",
                        NOW.plusSeconds(50),
                        NOW.plusSeconds(150),
                        null,
                        plan(approver),
                        NOW.plusSeconds(5));
        approvalCommands.decide(
                tenant,
                future.approvalCaseId(),
                approver,
                DecisionValue.APPROVE,
                null,
                1,
                NOW.plusSeconds(6));

        assertThat(exceptionService.effectiveExceptionIds(
                tenant,
                subject,
                Set.of(rule.id()),
                NOW.plusSeconds(40)))
                .isEmpty();
        assertThat(exceptionService.effectiveExceptionIds(
                tenant,
                subject,
                Set.of(rule.id()),
                NOW.plusSeconds(50)))
                .containsEntry(rule.id(), future.id());
    }

    @Test
    void finalRequestApprovalRevalidatesRevokedExceptionAndDefaultPolicyStillApplies() {
        UUID held = entitlement();
        UUID requested = entitlement();
        UUID subject = ids.nextId();
        UUID requester = ids.nextId();
        UUID exceptionApprover = ids.nextId();
        UUID requestApprover = ids.nextId();
        currentEffective.add(held);

        SoDRule rule = activateRule(
                "default-approval-with-deny-rule",
                held,
                requested,
                PolicyDecision.REQUIRE_APPROVAL,
                SoDAction.DENY,
                plan(requestApprover),
                NOW);

        GovernanceException exception =
                approveException(
                        subject,
                        rule.id(),
                        requester,
                        exceptionApprover,
                        NOW,
                        NOW.plusSeconds(3600),
                        null);

        AccessRequestCommandService commands =
                new AccessRequestCommandService(
                        requests,
                        evaluator,
                        approvalCommands,
                        (requestedTenant, item) -> { },
                        ids,
                        transactions);
        RequestDetail draft = commands.createDraft(
                tenant,
                requester,
                subject,
                List.of(new ItemSpec(
                        TargetKind.ENTITLEMENT,
                        requested,
                        PrincipalConstraintKind.ANY,
                        null,
                        null,
                        null)),
                NOW.plusSeconds(10));
        RequestDetail submitted = commands.submit(
                tenant,
                draft.request().id(),
                1,
                NOW.plusSeconds(11));
        RequestItem pending = commands.evaluateItem(
                tenant,
                submitted.items().getFirst().id(),
                submitted.items().getFirst().revision(),
                NOW.plusSeconds(12));
        assertThat(pending.state())
                .isEqualTo(ItemState.PENDING_APPROVAL);
        assertThat(pending.evaluationCode())
                .isEqualTo("approval_required");

        GovernanceException approved = exceptionService.find(
                tenant, exception.id()).orElseThrow();
        exceptionService.revoke(
                tenant,
                exception.id(),
                approved.revision(),
                NOW.plusSeconds(13));

        approvalCommands.decide(
                tenant,
                pending.approvalCaseId(),
                requestApprover,
                DecisionValue.APPROVE,
                null,
                1,
                NOW.plusSeconds(14));

        RequestItem denied = requests.findItem(
                tenant, pending.id()).orElseThrow();
        assertThat(denied.state())
                .isEqualTo(ItemState.DENIED);
        assertThat(denied.evaluationCode())
                .isEqualTo("sod_denied");
    }

    @Test
    void scheduledExpiryMaterializesIdempotentlyAfterSemanticExpiry() {
        UUID left = entitlement();
        UUID right = entitlement();
        UUID subject = ids.nextId();
        UUID requester = ids.nextId();
        UUID approver = ids.nextId();
        SoDRule rule = activateDenyRule(
                "short-lived",
                left,
                right,
                NOW);

        GovernanceException approved =
                approveException(
                        subject,
                        rule.id(),
                        requester,
                        approver,
                        NOW,
                        NOW.plusSeconds(10),
                        null);

        assertThat(approved.effectiveAt(
                NOW.plusSeconds(9)))
                .isTrue();
        assertThat(approved.effectiveAt(
                NOW.plusSeconds(10)))
                .isFalse();
        assertThat(exceptionService.effectiveExceptionIds(
                tenant,
                subject,
                Set.of(rule.id()),
                NOW.plusSeconds(10)))
                .isEmpty();

        var processor =
                new GovernanceExceptionExpiryProcessingService(
                        scheduledWork,
                        exceptionService,
                        Clock.fixed(
                                NOW.plusSeconds(11),
                                ZoneOffset.UTC),
                        "test-expiry-worker");
        assertThat(processor.processAvailable()
                .completed())
                .isEqualTo(1);

        GovernanceException expired =
                exceptionService.find(
                        tenant, approved.id())
                        .orElseThrow();
        assertThat(expired.lifecycleState())
                .isEqualTo(LifecycleState.EXPIRED);

        assertThat(processor.processAvailable()
                .claimed())
                .isZero();

        Integer changes = jdbc.queryForObject("""
                SELECT count(*)
                FROM platform.outbox_event
                WHERE tenant_id = ?
                  AND event_type =
                      'governance.exception-changed'
                  AND aggregate_id = ?
                """,
                Integer.class,
                tenant.tenantId(),
                approved.id());
        assertThat(changes).isEqualTo(2);
    }

    private GovernanceException approveException(
            UUID subject,
            UUID ruleId,
            UUID requester,
            UUID approver,
            Instant validFrom,
            Instant validUntil,
            UUID predecessor) {
        GovernanceException exception =
                exceptionService.request(
                        tenant,
                        subject,
                        ruleId,
                        requester,
                        "approved exception",
                        validFrom,
                        validUntil,
                        predecessor,
                        plan(approver),
                        NOW.plusSeconds(1));
        approvalCommands.decide(
                tenant,
                exception.approvalCaseId(),
                approver,
                DecisionValue.APPROVE,
                null,
                1,
                NOW.plusSeconds(2));
        return exceptionService.find(
                        tenant, exception.id())
                .orElseThrow();
    }

    private RequestItem evaluateRequest(
            UUID beneficiary,
            UUID requestedEntitlement,
            Instant at) {
        AccessRequestCommandService commands =
                new AccessRequestCommandService(
                        requests,
                        evaluator,
                        approvalCommands,
                        (requestedTenant, item) -> { },
                        ids,
                        transactions);
        RequestDetail draft = commands.createDraft(
                tenant,
                ids.nextId(),
                beneficiary,
                List.of(new ItemSpec(
                        TargetKind.ENTITLEMENT,
                        requestedEntitlement,
                        PrincipalConstraintKind.ANY,
                        null,
                        null,
                        null)),
                at);
        RequestDetail submitted = commands.submit(
                tenant,
                draft.request().id(),
                1,
                at.plusMillis(1));
        return commands.evaluateItem(
                tenant,
                submitted.items().getFirst().id(),
                submitted.items().getFirst().revision(),
                at.plusMillis(2));
    }

    private SoDRule activateDenyRule(
            String code,
            UUID left,
            UUID right,
            Instant at) {
        return activateRule(
                code,
                left,
                right,
                PolicyDecision.AUTHORIZE,
                SoDAction.DENY,
                null,
                at);
    }

    private SoDRule activateRule(
            String code,
            UUID left,
            UUID right,
            PolicyDecision defaultDecision,
            SoDAction action,
            PlanSpec approvalPlan,
            Instant at) {
        PolicyVersion draft = policyService.createDraft(
                tenant,
                PolicyKind.ACCESS_REQUEST,
                defaultDecision,
                List.of(new SoDRuleSpec(
                        code,
                        left,
                        right,
                        RiskSeverity.CRITICAL,
                        action)),
                approvalPlan,
                at);
        PolicyVersion ready = policyService.markReady(
                tenant,
                draft.id(),
                1,
                at.plusMillis(1));
        PolicyVersion active = policyService.activate(
                tenant,
                ready.id(),
                2,
                at.plusMillis(2));
        return policies.findRules(
                        tenant, active.id())
                .getFirst();
    }

    private UUID entitlement() {
        UUID id = ids.nextId();
        activeEntitlements.add(id);
        return id;
    }

    private static PlanSpec plan(
            UUID approver) {
        return new PlanSpec(List.of(
                new StageSpec(
                        DecisionMode.ANY_ONE,
                        List.of(approver))));
    }
}
