package io.wyrmgate.iam.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.access.application.LifecycleAccessPrivilegeGuard;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery;
import io.wyrmgate.iam.catalog.application.RoleExpansionQuery;
import io.wyrmgate.iam.governance.application.AccessRequestModels.*;
import io.wyrmgate.iam.governance.application.ApprovalModels.*;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.*;
import io.wyrmgate.iam.governance.persistence.JdbcAccessRequestRepository;
import io.wyrmgate.iam.governance.persistence.JdbcApprovalRepository;
import io.wyrmgate.iam.governance.persistence.JdbcGovernancePolicyRepository;
import io.wyrmgate.iam.governance.persistence.JdbcLifecycleAccessEvaluationEvidenceSink;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
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

class GovernancePolicyRiskSoDIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-29T02:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;

    private TenantContext tenant;
    private JdbcAccessRequestRepository requests;
    private JdbcApprovalRepository approvalRepository;
    private JdbcGovernancePolicyRepository policies;
    private GovernancePolicyService policyService;
    private GovernancePolicyEligibilityEvaluator evaluator;
    private ApprovalCaseStartService starter;
    private ApprovalCommandService approvals;

    private final Set<UUID> activeEntitlements =
            new HashSet<>();
    private final Set<UUID> currentEffective =
            new HashSet<>();
    private final Map<UUID,Set<UUID>> roleExpansions =
            new HashMap<>();

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
            (requestedTenant, roleId) -> {
                Set<UUID> entitlements =
                        roleExpansions.get(roleId);
                if (entitlements == null) {
                    return RoleExpansionQuery.Result.unavailable(
                            RoleExpansionQuery.Status.NOT_FOUND,
                            roleId);
                }
                UUID versionId = UUID.nameUUIDFromBytes(
                        ("version-" + roleId).getBytes());
                return RoleExpansionQuery.Result.available(
                        roleId,
                        entitlements.stream()
                                .map(entitlementId ->
                                        new RoleExpansionQuery
                                                .EntitlementPath(
                                                        entitlementId,
                                                        UUID.nameUUIDFromBytes(
                                                                ("target-" + entitlementId)
                                                                        .getBytes()),
                                                        List.of(versionId)))
                                .toList());
            };

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
                    Set<UUID> result = new HashSet<>(
                            entitlementIds);
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
                .isEqualTo("40");

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
                    governance.policy_evaluation,
                    governance.sod_conflict,
                    governance.risk_assessment,
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
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);

        tenant = new TenantContext(
                tenants.create(
                        "Policy SoD test", NOW).id());
        requests = new JdbcAccessRequestRepository(jdbc);
        approvalRepository =
                new JdbcApprovalRepository(jdbc);
        policies = new JdbcGovernancePolicyRepository(jdbc);
        policyService = new GovernancePolicyService(
                policies,
                catalog,
                identities,
                ids,
                transactions);
        evaluator =
                new GovernancePolicyEligibilityEvaluator(
                        policyService,
                        policies,
                        requests,
                        catalog,
                        roles,
                        effectiveAccess,
                        ids,
                        transactions,
                        Clock.fixed(
                                NOW.plusSeconds(30),
                                ZoneOffset.UTC));
        starter = new ApprovalCaseStartService(
                approvalRepository,
                ids,
                transactions);
        AccessRequestApprovalResultSink resultSink =
                new AccessRequestApprovalResultSink(
                        requests,
                        (requestedTenant, item) -> { },
                        evaluator,
                        approvalRepository,
                        starter,
                        new SubmittedRequestItemSink() {
                            @Override
                            public void submitted(
                                    TenantContext requestedTenant,
                                    RequestItem item) {}

                            @Override
                            public void retryEvaluation(
                                    TenantContext requestedTenant,
                                    RequestItem item) {}
                        });
        approvals = new ApprovalCommandService(
                approvalRepository,
                resultSink,
                ids,
                transactions);

        activeEntitlements.clear();
        currentEffective.clear();
        roleExpansions.clear();
    }

    @Test
    void policyVersionsAreValidatedImmutableAndAtomicallySuperseded() {
        UUID first = entitlement();
        UUID second = entitlement();
        UUID approver = ids.nextId();

        PolicyVersion draft = policyService.createDraft(
                tenant,
                PolicyKind.ACCESS_REQUEST,
                PolicyDecision.AUTHORIZE,
                List.of(rule(
                        "finance-conflict",
                        first,
                        second,
                        RiskSeverity.HIGH,
                        SoDAction.REQUIRE_APPROVAL)),
                plan(approver),
                NOW);

        PolicyVersion ready = policyService.markReady(
                tenant,
                draft.id(),
                1,
                NOW.plusSeconds(1));
        assertThat(ready.state())
                .isEqualTo(VersionState.READY);

        assertThatThrownBy(() -> policies.insertRule(
                tenant,
                new SoDRule(
                        ids.nextId(),
                        ready.id(),
                        "late-change",
                        first,
                        second,
                        RiskSeverity.CRITICAL,
                        SoDAction.DENY,
                        NOW.plusSeconds(2))))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(
                        "policy version content may only be inserted while DRAFT");

        PolicyVersion active = policyService.activate(
                tenant,
                ready.id(),
                2,
                NOW.plusSeconds(3));
        assertThat(active.state())
                .isEqualTo(VersionState.ACTIVE);

        PolicyVersion replacement =
                policyService.createDraft(
                        tenant,
                        PolicyKind.ACCESS_REQUEST,
                        PolicyDecision.AUTHORIZE,
                        List.of(),
                        null,
                        NOW.plusSeconds(4));
        replacement = policyService.markReady(
                tenant,
                replacement.id(),
                1,
                NOW.plusSeconds(5));
        policyService.activate(
                tenant,
                replacement.id(),
                2,
                NOW.plusSeconds(6));

        assertThat(policies.findVersion(
                tenant, active.id()).orElseThrow().state())
                .isEqualTo(VersionState.SUPERSEDED);
        assertThat(policies.findActiveVersion(
                tenant, PolicyKind.ACCESS_REQUEST))
                .get()
                .extracting(PolicyVersion::id)
                .isEqualTo(replacement.id());
    }

    @Test
    void symmetricRuleCanonicalizationMatchesPostgresUuidOrdering() {
        UUID highBit = UUID.fromString(
                "80000000-0000-0000-0000-000000000000");
        UUID lower = UUID.fromString(
                "7fffffff-ffff-ffff-ffff-ffffffffffff");

        PolicyVersion draft = policyService.createDraft(
                tenant,
                PolicyKind.ACCESS_REQUEST,
                PolicyDecision.AUTHORIZE,
                List.of(rule(
                        "uuid-order",
                        highBit,
                        lower,
                        RiskSeverity.LOW,
                        SoDAction.DENY)),
                null,
                NOW);

        SoDRule stored = policies.findRules(
                        tenant, draft.id())
                .getFirst();
        assertThat(stored.leftEntitlementId())
                .isEqualTo(lower);
        assertThat(stored.rightEntitlementId())
                .isEqualTo(highBit);
    }

    @Test
    void lifecycleAccessGuardPersistsDecisionAndSodEvidence() {
        UUID existing = entitlement();
        UUID requested = entitlement();
        UUID identityId = ids.nextId();
        UUID lifecycleRuleId = ids.nextId();
        UUID correlationId = ids.nextId();
        UUID causationId = ids.nextId();
        currentEffective.add(existing);
        PolicyVersion active = activate(
                PolicyDecision.AUTHORIZE,
                List.of(rule(
                        "lifecycle-toxic-pair",
                        existing,
                        requested,
                        RiskSeverity.HIGH,
                        SoDAction.DENY)),
                null,
                NOW);

        LifecycleAccessPrivilegeGuard guard =
                new GovernanceLifecycleAccessPrivilegeGuard(
                        policyService,
                        catalog,
                        roles,
                        effectiveAccess,
                        (requestedTenant, subjectIdentityId, ruleIds, at) -> Map.of(),
                        new JdbcLifecycleAccessEvaluationEvidenceSink(jdbc),
                        ids);

        LifecycleAccessPrivilegeGuard.Result result = guard.evaluate(
                tenant,
                identityId,
                lifecycleRuleId,
                AccessAssignment.TargetKind.ENTITLEMENT,
                requested,
                NOW.plusSeconds(1),
                correlationId,
                causationId);

        assertThat(result.decision())
                .isEqualTo(LifecycleAccessPrivilegeGuard.Decision.DENY);

        Map<String,Object> evaluation = jdbc.queryForMap(
                """
                SELECT identity_id, lifecycle_rule_id,
                       governance_policy_version_id,
                       target_kind, target_id, decision,
                       conflict_count, correlation_id, causation_id
                FROM governance.lifecycle_access_evaluation
                WHERE tenant_id = ? AND lifecycle_rule_id = ?
                """,
                tenant.tenantId(),
                lifecycleRuleId);
        assertThat(evaluation.get("identity_id")).isEqualTo(identityId);
        assertThat(evaluation.get("governance_policy_version_id")).isEqualTo(active.id());
        assertThat(evaluation.get("target_kind")).isEqualTo("ENTITLEMENT");
        assertThat(evaluation.get("target_id")).isEqualTo(requested);
        assertThat(evaluation.get("decision")).isEqualTo("DENY");
        assertThat(evaluation.get("conflict_count")).isEqualTo(1);
        assertThat(evaluation.get("correlation_id")).isEqualTo(correlationId);
        assertThat(evaluation.get("causation_id")).isEqualTo(causationId);

        Integer conflicts = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM governance.lifecycle_access_sod_conflict c
                JOIN governance.lifecycle_access_evaluation e
                  ON e.tenant_id = c.tenant_id AND e.id = c.evaluation_id
                WHERE e.tenant_id = ? AND e.lifecycle_rule_id = ?
                """,
                Integer.class,
                tenant.tenantId(),
                lifecycleRuleId);
        assertThat(conflicts).isEqualTo(1);

        UUID evaluationId = jdbc.queryForObject(
                """
                SELECT id FROM governance.lifecycle_access_evaluation
                WHERE tenant_id = ? AND lifecycle_rule_id = ?
                """,
                UUID.class,
                tenant.tenantId(),
                lifecycleRuleId);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE governance.lifecycle_access_evaluation SET evaluation_code = 'changed' WHERE tenant_id = ? AND id = ?",
                tenant.tenantId(),
                evaluationId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("governance evaluation evidence is immutable");
    }

    @Test
    void currentEffectiveConflictDeniesAndPersistsImmutableEvidence() {
        UUID existing = entitlement();
        UUID requested = entitlement();
        currentEffective.add(existing);
        activate(
                PolicyDecision.AUTHORIZE,
                List.of(rule(
                        "toxic-pair",
                        existing,
                        requested,
                        RiskSeverity.CRITICAL,
                        SoDAction.DENY)),
                null,
                NOW);

        AccessRequestCommandService commands =
                requestCommands();
        RequestDetail draft = commands.createDraft(
                tenant,
                ids.nextId(),
                ids.nextId(),
                List.of(item(requested)),
                NOW.plusSeconds(1));
        RequestDetail submitted = commands.submit(
                tenant,
                draft.request().id(),
                1,
                NOW.plusSeconds(2));
        RequestItem denied = commands.evaluateItem(
                tenant,
                submitted.items().getFirst().id(),
                2,
                NOW.plusSeconds(3));

        assertThat(denied.state())
                .isEqualTo(ItemState.DENIED);
        assertThat(denied.evaluationCode())
                .isEqualTo("sod_denied");
        assertThat(jdbc.queryForObject("""
                SELECT decision
                FROM governance.policy_evaluation
                WHERE tenant_id = ? AND request_item_id = ?
                """,
                String.class,
                tenant.tenantId(),
                denied.id()))
                .isEqualTo("DENY");
        assertThat(jdbc.queryForObject("""
                SELECT severity
                FROM governance.risk_assessment
                WHERE tenant_id = ? AND request_item_id = ?
                """,
                String.class,
                tenant.tenantId(),
                denied.id()))
                .isEqualTo("CRITICAL");
        assertThat(jdbc.queryForObject("""
                SELECT conflict_source
                FROM governance.sod_conflict c
                JOIN governance.risk_assessment r
                  ON r.tenant_id = c.tenant_id
                 AND r.id = c.risk_assessment_id
                WHERE c.tenant_id = ? AND r.request_item_id = ?
                """,
                String.class,
                tenant.tenantId(),
                denied.id()))
                .isEqualTo("CURRENT_ACCESS");

        UUID evidenceId = jdbc.queryForObject("""
                SELECT id
                FROM governance.policy_evaluation
                WHERE tenant_id = ? AND request_item_id = ?
                """,
                UUID.class,
                tenant.tenantId(),
                denied.id());
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM governance.policy_evaluation
                WHERE tenant_id = ? AND id = ?
                """,
                tenant.tenantId(),
                evidenceId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(
                        "governance evaluation evidence is immutable");
    }

    @Test
    void roleAndOtherRequestItemProduceApprovalRequiredConflict() {
        UUID left = entitlement();
        UUID right = entitlement();
        UUID roleId = ids.nextId();
        roleExpansions.put(roleId, Set.of(right));
        UUID approver = ids.nextId();

        activate(
                PolicyDecision.AUTHORIZE,
                List.of(rule(
                        "cross-item",
                        left,
                        right,
                        RiskSeverity.HIGH,
                        SoDAction.REQUIRE_APPROVAL)),
                plan(approver),
                NOW);

        AccessRequestCommandService commands =
                requestCommands();
        RequestDetail draft = commands.createDraft(
                tenant,
                ids.nextId(),
                ids.nextId(),
                List.of(
                        item(left),
                        new ItemSpec(
                                TargetKind.ROLE,
                                roleId,
                                PrincipalConstraintKind.ANY,
                                null,
                                null,
                                null)),
                NOW.plusSeconds(1));
        RequestDetail submitted = commands.submit(
                tenant,
                draft.request().id(),
                1,
                NOW.plusSeconds(2));
        RequestItem roleItem =
                submitted.items().get(1);
        RequestItem pending = commands.evaluateItem(
                tenant,
                roleItem.id(),
                roleItem.revision(),
                NOW.plusSeconds(3));

        assertThat(pending.state())
                .isEqualTo(ItemState.PENDING_APPROVAL);
        assertThat(pending.approvalCaseId())
                .isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT conflict_source
                FROM governance.sod_conflict c
                JOIN governance.risk_assessment r
                  ON r.tenant_id = c.tenant_id
                 AND r.id = c.risk_assessment_id
                WHERE c.tenant_id = ? AND r.request_item_id = ?
                """,
                String.class,
                tenant.tenantId(),
                pending.id()))
                .isEqualTo("REQUEST");
    }

    @Test
    void missingActivePolicyRemainsEvaluating() {
        UUID requested = entitlement();
        AccessRequestCommandService commands =
                requestCommands();
        RequestDetail draft = commands.createDraft(
                tenant,
                ids.nextId(),
                ids.nextId(),
                List.of(item(requested)),
                NOW);
        RequestDetail submitted = commands.submit(
                tenant,
                draft.request().id(),
                1,
                NOW.plusSeconds(1));
        RequestItem evaluating = commands.evaluateItem(
                tenant,
                submitted.items().getFirst().id(),
                2,
                NOW.plusSeconds(2));

        assertThat(evaluating.state())
                .isEqualTo(ItemState.EVALUATING);
        assertThat(evaluating.evaluationCode())
                .isEqualTo(
                        "active_access_request_policy_unavailable");
    }

    @Test
    void finalApprovalAuthorizesWhenCurrentPlanFingerprintIsUnchanged() {
        UUID requested = entitlement();
        UUID approver = ids.nextId();
        UUID requester = ids.nextId();

        activate(
                PolicyDecision.REQUIRE_APPROVAL,
                List.of(),
                plan(approver),
                NOW);

        AccessRequestCommandService commands =
                requestCommands();
        RequestDetail draft = commands.createDraft(
                tenant,
                requester,
                ids.nextId(),
                List.of(item(requested)),
                NOW.plusSeconds(1));
        RequestDetail submitted = commands.submit(
                tenant,
                draft.request().id(),
                1,
                NOW.plusSeconds(2));
        RequestItem pending = commands.evaluateItem(
                tenant,
                submitted.items().getFirst().id(),
                2,
                NOW.plusSeconds(3));

        approvals.decide(
                tenant,
                pending.approvalCaseId(),
                approver,
                DecisionValue.APPROVE,
                null,
                1,
                NOW.plusSeconds(4));

        RequestItem authorized = requests.findItem(
                        tenant, pending.id())
                .orElseThrow();
        assertThat(authorized.state())
                .isEqualTo(ItemState.AUTHORIZED);
        assertThat(authorized.approvalCaseId())
                .isEqualTo(pending.approvalCaseId());
        assertThat(authorized.evaluationCode())
                .isEqualTo("approval_required");
    }

    @Test
    void finalApprovalRevalidatesChangedPlanThenCurrentDeny() {
        UUID requested = entitlement();
        UUID firstApprover = ids.nextId();
        UUID secondApprover = ids.nextId();
        UUID requester = ids.nextId();

        activate(
                PolicyDecision.REQUIRE_APPROVAL,
                List.of(),
                plan(firstApprover),
                NOW);

        AccessRequestCommandService commands =
                requestCommands();
        RequestDetail draft = commands.createDraft(
                tenant,
                requester,
                ids.nextId(),
                List.of(item(requested)),
                NOW.plusSeconds(1));
        RequestDetail submitted = commands.submit(
                tenant,
                draft.request().id(),
                1,
                NOW.plusSeconds(2));
        RequestItem firstPending = commands.evaluateItem(
                tenant,
                submitted.items().getFirst().id(),
                2,
                NOW.plusSeconds(3));

        activate(
                PolicyDecision.REQUIRE_APPROVAL,
                List.of(),
                plan(secondApprover),
                NOW.plusSeconds(4));

        ApprovalCase oldApproved = approvals.decide(
                tenant,
                firstPending.approvalCaseId(),
                firstApprover,
                DecisionValue.APPROVE,
                null,
                1,
                NOW.plusSeconds(5));
        assertThat(oldApproved.state())
                .isEqualTo(CaseState.APPROVED);

        RequestItem replacementPending =
                requests.findItem(
                        tenant,
                        firstPending.id())
                        .orElseThrow();
        assertThat(replacementPending.state())
                .isEqualTo(ItemState.PENDING_APPROVAL);
        assertThat(replacementPending.approvalCaseId())
                .isNotEqualTo(
                        firstPending.approvalCaseId());

        ApprovalCase replacement =
                approvalRepository.findCase(
                        tenant,
                        replacementPending.approvalCaseId())
                        .orElseThrow();
        ApprovalPlan replacementPlan =
                approvalRepository.findPlan(
                        tenant,
                        replacement.id());
        ApprovalStage replacementStage =
                approvalRepository.findStages(
                        tenant,
                        replacementPlan.id())
                        .getFirst();
        assertThat(approvalRepository.findApprovers(
                tenant,
                replacementStage.id()))
                .extracting(
                        ApprovalApprover::approverIdentityId)
                .containsExactly(secondApprover);

        activate(
                PolicyDecision.DENY,
                List.of(),
                null,
                NOW.plusSeconds(6));

        ApprovalCase secondApproved = approvals.decide(
                tenant,
                replacement.id(),
                secondApprover,
                DecisionValue.APPROVE,
                null,
                1,
                NOW.plusSeconds(7));
        assertThat(secondApproved.state())
                .isEqualTo(CaseState.APPROVED);

        RequestItem denied = requests.findItem(
                        tenant,
                        firstPending.id())
                .orElseThrow();
        assertThat(denied.state())
                .isEqualTo(ItemState.DENIED);
        assertThat(denied.evaluationCode())
                .isEqualTo("policy_denied");
    }

    private AccessRequestCommandService requestCommands() {
        return new AccessRequestCommandService(
                requests,
                evaluator,
                approvals,
                (requestedTenant, item) -> { },
                ids,
                transactions);
    }

    private PolicyVersion activate(
            PolicyDecision defaultDecision,
            List<SoDRuleSpec> rules,
            PlanSpec plan,
            Instant at) {
        PolicyVersion draft = policyService.createDraft(
                tenant,
                PolicyKind.ACCESS_REQUEST,
                defaultDecision,
                rules,
                plan,
                at);
        PolicyVersion ready = policyService.markReady(
                tenant,
                draft.id(),
                1,
                at.plusMillis(1));
        return policyService.activate(
                tenant,
                ready.id(),
                2,
                at.plusMillis(2));
    }

    private UUID entitlement() {
        UUID id = ids.nextId();
        activeEntitlements.add(id);
        return id;
    }

    private static ItemSpec item(
            UUID entitlementId) {
        return new ItemSpec(
                TargetKind.ENTITLEMENT,
                entitlementId,
                PrincipalConstraintKind.ANY,
                null,
                null,
                null);
    }

    private static SoDRuleSpec rule(
            String code,
            UUID left,
            UUID right,
            RiskSeverity severity,
            SoDAction action) {
        return new SoDRuleSpec(
                code,
                left,
                right,
                severity,
                action);
    }

    private static PlanSpec plan(
            UUID approver) {
        return new PlanSpec(List.of(
                new StageSpec(
                        DecisionMode.ANY_ONE,
                        List.of(approver))));
    }
}
