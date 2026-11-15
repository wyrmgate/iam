package io.wyrmgate.iam.governance.application;


import static org.assertj.core.api.Assertions.assertThat;

import io.wyrmgate.iam.access.application.AccessAssignmentBoundaryScheduler;
import io.wyrmgate.iam.access.application.AccessAssignmentCommandException;
import io.wyrmgate.iam.access.application.AccessAssignmentCommandService;
import io.wyrmgate.iam.access.application.AccessAssignmentFactSink;
import io.wyrmgate.iam.access.application.AccessIntentCommand;
import io.wyrmgate.iam.access.application.AccessIntentCommandService;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentBoundaryScheduler;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentFactSink;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentRepository;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.application.RoleExpansionQueryService;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogRepository;
import io.wyrmgate.iam.catalog.persistence.JdbcRoleRepository;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityResult;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemSpec;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemState;
import io.wyrmgate.iam.governance.application.AccessRequestModels.PrincipalConstraintKind;
import io.wyrmgate.iam.governance.application.AccessRequestModels.TargetKind;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionValue;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import io.wyrmgate.iam.governance.persistence.JdbcAccessRequestRepository;
import io.wyrmgate.iam.governance.persistence.JdbcApprovalRepository;
import io.wyrmgate.iam.governance.persistence.JdbcAuthorizedAccessIntentSink;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQueryService;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.PrincipalCommandService;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalRepository;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.tenancy.persistence.TenantTestFixture;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class AccessRequestAccessApplicationIntegrationTest {

    private static final Instant NOW =
            Instant.parse("2026-09-28T10:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static TenantTestFixture tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcScheduledWorkRepository scheduledWork;
    private static TransactionExecutor transactions;

    private static JdbcIdentityRepository identityRepository;
    private static IdentityCommandService identities;
    private static JdbcPrincipalRepository principalRepository;
    private static PrincipalCommandService principals;
    private static IdentityAccessReferenceQueryService identityReferences;

    private static JdbcCatalogRepository catalogRepository;
    private static CatalogCommandService catalog;
    private static RoleExpansionQueryService roleExpansion;

    private static JdbcAccessAssignmentRepository assignmentRepository;
    private static AccessIntentCommandService accessIntent;
    private static io.wyrmgate.iam.access.application.AccessAdmissionConflictQuery conflicts;

    private TenantContext tenant;
    private JdbcAccessRequestRepository requestRepository;
    private JdbcApprovalRepository approvalRepository;
    private AuthorizedAccessIntentSink authorizedAccess;
    private ApprovalCommandService approvals;
    private AccessRequestEligibilityEvaluator currentEvaluator;

    @BeforeAll
    static void start() {
        io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.reset();
        var dataSource = io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new TenantTestFixture(jdbc, ids);
        outbox = new JdbcOutboxRepository(jdbc);
        scheduledWork = new JdbcScheduledWorkRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));

        identityRepository = new JdbcIdentityRepository(jdbc);
        identities = new IdentityCommandService(
                identityRepository,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions,
                (tenant, identityId, at) -> {});
        principalRepository = new JdbcPrincipalRepository(jdbc);
        identityReferences =
                new IdentityAccessReferenceQueryService(
                        identityRepository,
                        principalRepository);

        catalogRepository = new JdbcCatalogRepository(jdbc);
        catalog = new CatalogCommandService(
                catalogRepository,
                io.wyrmgate.iam.testsupport.CatalogAccessTestSupport.propagationStarter(
                        jdbc, ids, transactions),
                ids,
                transactions);
        var roleRepository = new JdbcRoleRepository(jdbc);
        roleExpansion = new RoleExpansionQueryService(
                catalogRepository,
                roleRepository);
        var catalogAvailability =
                io.wyrmgate.iam.testsupport.CatalogAccessTestSupport.availability(
                        catalogRepository, roleRepository);

        principals = new PrincipalCommandService(
                principalRepository,
                identityRepository,
                new CatalogQueryService(catalogRepository),
                new JdbcPrincipalFactSink(outbox, ids),
                ids,
                transactions,
                (tenant, principalId, identityId, at) -> {});

        assignmentRepository =
                new JdbcAccessAssignmentRepository(jdbc);
        AccessAssignmentFactSink facts =
                new JdbcAccessAssignmentFactSink(outbox, ids);
        AccessAssignmentBoundaryScheduler boundaries =
                new JdbcAccessAssignmentBoundaryScheduler(
                        scheduledWork);
        var assignmentCommands =
                new AccessAssignmentCommandService(
                        assignmentRepository,
                        identityReferences,
                        new CatalogQueryService(
                                catalogRepository),
                        catalogAvailability,
                        roleExpansion,
                        facts,
                        boundaries,
                        ids,
                        transactions, io.wyrmgate.iam.testsupport.AdmissionTestSupport.coordinator(jdbc, transactions), io.wyrmgate.iam.testsupport.AdmissionTestSupport.allowForFixture());
        conflicts = new io.wyrmgate.iam.access.application.AccessAdmissionConflictQueryService(assignmentRepository, identityReferences, catalogAvailability, roleExpansion,
                new io.wyrmgate.iam.access.application.EffectiveAccessSemanticSupport(assignmentRepository, identityReferences, new CatalogQueryService(catalogRepository), catalogAvailability, roleExpansion));
        accessIntent = new AccessIntentCommandService(
                assignmentRepository, assignmentCommands);
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
                    access.access_assignment,
                    access.effective_access_support_role_version,
                    access.effective_access_support,
                    access.effective_access,
                    access.desired_grant_state,
                    access.desired_principal_state,
                    platform.scheduled_work,
                    platform.outbox_event,
                    identity.principal,
                    identity.person_profile,
                    identity.service_profile,
                    identity.workload_profile,
                    identity.identity,
                    catalog.role_version_member,
                    catalog.role_version,
                    catalog.role,
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    tenancy.tenant
                CASCADE
                """);

        tenant = new TenantContext(
                tenants.createActive("Request application", NOW).id());
        requestRepository =
                new JdbcAccessRequestRepository(jdbc);
        approvalRepository =
                new JdbcApprovalRepository(jdbc);
        authorizedAccess =
                new JdbcAuthorizedAccessIntentSink(
                        outbox, ids);
        approvals = new ApprovalCommandService(
                approvalRepository,
                new AccessRequestApprovalResultSink(
                        requestRepository,
                        (t, request, item) -> currentEvaluator.evaluate(t, request, item),
                        new io.wyrmgate.iam.governance.application.RequestAdmissionDecisionService(requestRepository, approvalRepository, new io.wyrmgate.iam.governance.application.ApprovalCaseStartService(approvalRepository, ids, transactions), authorizedAccess),
                        new io.wyrmgate.iam.governance.persistence.JdbcSubmittedRequestItemSink(new io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository(jdbc), ids)),
                ids,
                transactions);
    }

    @Test
    void directAuthorizationAppliesSpecificScheduledAccessAndRecordsTrace() {
        Identity requester = identity("Requester");
        Identity beneficiary = identity("Beneficiary");
        var targetAndEntitlement = entitlement();
        var principal = principals.create(
                tenant,
                targetAndEntitlement.targetId(),
                "beneficiary-native",
                beneficiary.id(),
                NOW,
                ids.nextId(),
                null);
        Instant validFrom = NOW.plusSeconds(3600);
        Instant validUntil = NOW.plusSeconds(7200);

        AccessRequestCommandService requests =
                requestService((t, request, item) ->
                        EligibilityResult.authorized(contextFor(t, request, item)));
        var draft = requests.createDraft(
                tenant,
                requester.id(),
                beneficiary.id(),
                List.of(new ItemSpec(
                        TargetKind.ENTITLEMENT,
                        targetAndEntitlement.entitlementId(),
                        PrincipalConstraintKind.SPECIFIC,
                        principal.id(),
                        validFrom,
                        validUntil)),
                NOW);
        var submitted = requests.submit(
                tenant,
                draft.request().id(),
                1,
                NOW.plusSeconds(1));
        var authorized = requests.evaluateItem(
                tenant,
                submitted.items().getFirst().id(),
                2,
                NOW.plusSeconds(2));

        assertThat(authorized.state())
                .isEqualTo(ItemState.AUTHORIZED);
        assertThat(authorized.accessAssignmentId()).isNull();
        assertThat(assignmentRepository.findByProvenance(
                tenant,
                AccessAssignment.ProvenanceKind.REQUEST_ITEM,
                authorized.id())).isEmpty();

        var processor = processor(
                accessIntent,
                NOW.plusSeconds(3));
        assertThat(processor.processAvailable().processed())
                .isEqualTo(1);

        var applied = requestRepository.findItem(
                        tenant, authorized.id())
                .orElseThrow();
        assertThat(applied.state())
                .isEqualTo(ItemState.APPLIED);
        assertThat(applied.accessAssignmentId()).isNotNull();

        AccessAssignment assignment =
                assignmentRepository.findById(
                        tenant,
                        applied.accessAssignmentId())
                .orElseThrow();
        assertThat(assignment.provenanceKind())
                .isEqualTo(
                        AccessAssignment.ProvenanceKind.REQUEST_ITEM);
        assertThat(assignment.provenanceRefId())
                .isEqualTo(authorized.id());
        assertThat(assignment.identityId())
                .isEqualTo(beneficiary.id());
        assertThat(assignment.specificPrincipalId())
                .isEqualTo(principal.id());
        assertThat(assignment.principalConstraintKind())
                .isEqualTo(
                        AccessAssignment.PrincipalConstraintKind.SPECIFIC);
        assertThat(assignment.validFrom())
                .isEqualTo(validFrom);
        assertThat(assignment.validUntil())
                .isEqualTo(validUntil);
        assertThat(assignment.lifecycleState())
                .isEqualTo(
                        AccessAssignment.LifecycleState.SCHEDULED);

        AccessIntentCommand.Result replay =
                accessIntent.applyRequestedAccess(
                        tenant,
                        requestedAccess(
                                applied,
                                beneficiary.id()),
                        admissionFor(applied),
                        NOW.plusSeconds(4));
        assertThat(replay.accessAssignmentId())
                .isEqualTo(assignment.id());
        assertThat(requestDerivedAssignmentCount(
                authorized.id())).isEqualTo(1);
    }

    @Test
    void approvalAuthorizationSurvivesFailureAndRetryCreatesOneAssignment() {
        Identity requester = identity("Requester");
        Identity beneficiary = identity("Beneficiary");
        Identity approver = identity("Approver");
        var targetAndEntitlement = entitlement();

        AccessRequestCommandService requests =
                requestService((t, request, item) ->
                        EligibilityResult.approvalRequired(new PlanSpec(List.of(
                                        new StageSpec(
                                                DecisionMode.ANY_ONE,
                                                List.of(
                                                        approver.id())))), contextFor(t, request, item)));
        var draft = requests.createDraft(
                tenant,
                requester.id(),
                beneficiary.id(),
                List.of(new ItemSpec(
                        TargetKind.ENTITLEMENT,
                        targetAndEntitlement.entitlementId(),
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
        var pending = requests.evaluateItem(
                tenant,
                submitted.items().getFirst().id(),
                2,
                NOW.plusSeconds(2));
        approvals.decide(
                tenant,
                pending.approvalCaseId(),
                approver.id(),
                DecisionValue.APPROVE,
                null,
                1,
                NOW.plusSeconds(3));

        var authorized = requestRepository.findItem(
                        tenant, pending.id())
                .orElseThrow();
        assertThat(authorized.state())
                .isEqualTo(ItemState.AUTHORIZED);

        AtomicBoolean failFirst = new AtomicBoolean(true);
        AccessIntentCommand flaky = (requestedTenant, request, admission, now) -> {
            if (failFirst.getAndSet(false)) {
                throw new IllegalStateException(
                        "temporary Access failure");
            }
            return accessIntent.applyRequestedAccess(
                    requestedTenant, request, admission, now);
        };

        var firstAttempt = processor(
                flaky, NOW.plusSeconds(4));
        assertThat(firstAttempt.processAvailable().failed())
                .isEqualTo(1);
        assertThat(requestRepository.findItem(
                        tenant, authorized.id())
                .orElseThrow().state())
                .isEqualTo(ItemState.AUTHORIZED);
        assertThat(requestDerivedAssignmentCount(
                authorized.id())).isZero();

        var retry = processor(
                flaky, NOW.plusSeconds(10));
        assertThat(retry.processAvailable().processed())
                .isEqualTo(1);

        var applied = requestRepository.findItem(
                        tenant, authorized.id())
                .orElseThrow();
        assertThat(applied.state())
                .isEqualTo(ItemState.APPLIED);
        assertThat(requestDerivedAssignmentCount(
                authorized.id())).isEqualTo(1);

        AccessIntentCommand.Result replay =
                accessIntent.applyRequestedAccess(
                        tenant,
                        requestedAccess(
                                applied,
                                beneficiary.id()),
                        admissionFor(applied),
                        NOW.plusSeconds(11));
        assertThat(replay.accessAssignmentId())
                .isEqualTo(applied.accessAssignmentId());
        assertThat(requestDerivedAssignmentCount(
                authorized.id())).isEqualTo(1);
    }

    @Test
    void permanentAccessRejectionTerminatesAuthorizedItemWithoutRetryLoop() {
        Identity requester = identity("Requester");
        Identity beneficiary = identity("Beneficiary");
        var targetAndEntitlement = entitlement();

        AccessRequestCommandService requests =
                requestService((t, request, item) ->
                        EligibilityResult.authorized(contextFor(t, request, item)));
        var draft = requests.createDraft(
                tenant,
                requester.id(),
                beneficiary.id(),
                List.of(new ItemSpec(
                        TargetKind.ENTITLEMENT,
                        targetAndEntitlement.entitlementId(),
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
        var authorized = requests.evaluateItem(
                tenant,
                submitted.items().getFirst().id(),
                2,
                NOW.plusSeconds(2));

        AccessIntentCommand terminal = (requestedTenant, request, admission, now) -> {
            throw new AccessAssignmentCommandException(
                    "identity_not_access_eligible",
                    "Identity is no longer access-eligible");
        };

        var first = processor(
                terminal, NOW.plusSeconds(3))
                .processAvailable();
        assertThat(first.processed()).isEqualTo(1);
        assertThat(first.failed()).isZero();

        var cancelled = requestRepository.findItem(
                        tenant, authorized.id())
                .orElseThrow();
        assertThat(cancelled.state())
                .isEqualTo(ItemState.CANCELLED);
        assertThat(cancelled.accessAssignmentId()).isNull();
        assertThat(requestDerivedAssignmentCount(
                authorized.id())).isZero();

        assertThat(processor(
                terminal, NOW.plusSeconds(10))
                .processAvailable().claimed())
                .isZero();
    }

    @Test
    void endedValidityTerminatesAuthorizedItemAsExpired() {
        Identity requester = identity("Requester");
        Identity beneficiary = identity("Beneficiary");
        var targetAndEntitlement = entitlement();

        AccessRequestCommandService requests =
                requestService((t, request, item) ->
                        EligibilityResult.authorized(contextFor(t, request, item)));
        var draft = requests.createDraft(
                tenant,
                requester.id(),
                beneficiary.id(),
                List.of(new ItemSpec(
                        TargetKind.ENTITLEMENT,
                        targetAndEntitlement.entitlementId(),
                        PrincipalConstraintKind.ANY,
                        null,
                        null,
                        NOW.plusSeconds(60))),
                NOW);
        var submitted = requests.submit(
                tenant,
                draft.request().id(),
                1,
                NOW.plusSeconds(1));
        var authorized = requests.evaluateItem(
                tenant,
                submitted.items().getFirst().id(),
                2,
                NOW.plusSeconds(2));

        AccessIntentCommand expired = (requestedTenant, request, admission, now) -> {
            throw new AccessAssignmentCommandException(
                    "validity_already_ended",
                    "Validity already ended");
        };

        var batch = processor(
                expired, NOW.plusSeconds(61))
                .processAvailable();
        assertThat(batch.processed()).isEqualTo(1);
        assertThat(requestRepository.findItem(
                        tenant, authorized.id())
                .orElseThrow().state())
                .isEqualTo(ItemState.EXPIRED);
        assertThat(requestDerivedAssignmentCount(
                authorized.id())).isZero();
    }

    @Test
    void queuedAuthorizationRevalidatesBeforeCreationAndRecoversCommittedResultBeforePolicy() {
        Identity requester = identity("Requester");
        Identity beneficiary = identity("Beneficiary");
        var target = entitlement();
        var commands = requestService((t, r, item) -> EligibilityResult.authorized(contextFor(t, r, item)));
        var draft = commands.createDraft(tenant, requester.id(), beneficiary.id(),
                List.of(new ItemSpec(TargetKind.ENTITLEMENT, target.entitlementId(),
                        PrincipalConstraintKind.ANY, null, null, null)), NOW);
        var submitted = commands.submit(tenant, draft.request().id(), 1, NOW.plusSeconds(1));
        var authorized = commands.evaluateItem(tenant, submitted.items().getFirst().id(), 2, NOW.plusSeconds(2));
        currentEvaluator = (t, r, item) -> EligibilityResult.unavailable("policy_unavailable");
        assertThat(processor(accessIntent, NOW.plusSeconds(3)).processAvailable().failed()).isEqualTo(1);
        assertThat(requestDerivedAssignmentCount(authorized.id())).isZero();

        currentEvaluator = (t, r, item) -> EligibilityResult.authorized(contextFor(t, r, item));
        // Commit Access in the admission transaction, then simulate a worker dying before APPLIED.
        var request = requestRepository.findRequest(tenant, authorized.accessRequestId()).orElseThrow();
        var context = contextFor(tenant, request, authorized);
        io.wyrmgate.iam.testsupport.AdmissionTestSupport.coordinator(jdbc, transactions).execute(tenant, beneficiary.id(), permit -> accessIntent.applyRequestedAccess(tenant,
                new AccessIntentCommand.RequestedAccess(authorized.id(), beneficiary.id(), AccessIntentCommand.TargetKind.ENTITLEMENT,
                        null, authorized.entitlementId(), AccessIntentCommand.PrincipalConstraintKind.ANY, null, authorized.validFrom(), authorized.validUntil()),
                new AccessIntentCommand.Admission(context.identityEpoch(), context.targetFingerprint()), NOW.plusSeconds(9)));
        assertThat(requestDerivedAssignmentCount(authorized.id())).isEqualTo(1);
        currentEvaluator = (t, r, item) -> EligibilityResult.denied("policy_denied");
        assertThat(processor(accessIntent, NOW.plusSeconds(15)).processAvailable().processed()).isEqualTo(1);
        assertThat(requestRepository.findItem(tenant, authorized.id()).orElseThrow().state()).isEqualTo(ItemState.APPLIED);
        assertThat(requestDerivedAssignmentCount(authorized.id())).isEqualTo(1);
    }

    @Test
    void queuedAuthorizationCannotApplyWhenCurrentPolicyDenies() {
        Identity requester = identity("Requester");
        Identity beneficiary = identity("Beneficiary");
        var target = entitlement();
        var commands = requestService((t, r, item) -> EligibilityResult.authorized(contextFor(t, r, item)));
        var draft = commands.createDraft(tenant, requester.id(), beneficiary.id(),
                List.of(new ItemSpec(TargetKind.ENTITLEMENT, target.entitlementId(),
                        PrincipalConstraintKind.ANY, null, null, null)), NOW);
        var submitted = commands.submit(tenant, draft.request().id(), 1, NOW.plusSeconds(1));
        var authorized = commands.evaluateItem(tenant, submitted.items().getFirst().id(), 2, NOW.plusSeconds(2));
        currentEvaluator = (t, r, item) -> EligibilityResult.denied("policy_denied");
        assertThat(processor(accessIntent, NOW.plusSeconds(3)).processAvailable().processed()).isEqualTo(1);
        assertThat(requestRepository.findItem(tenant, authorized.id()).orElseThrow().state()).isEqualTo(ItemState.DENIED);
        assertThat(requestDerivedAssignmentCount(authorized.id())).isZero();
    }

    @Test
    void queuedAuthorizationCannotCrossIdentityEligibilityEpoch() {
        Identity requester = identity("Requester");
        Identity beneficiary = identity("Beneficiary");
        var target = entitlement();
        var commands = requestService((t, r, item) -> EligibilityResult.authorized(contextFor(t, r, item)));
        var draft = commands.createDraft(tenant, requester.id(), beneficiary.id(),
                List.of(new ItemSpec(TargetKind.ENTITLEMENT, target.entitlementId(),
                        PrincipalConstraintKind.ANY, null, null, null)), NOW);
        var submitted = commands.submit(tenant, draft.request().id(), 1, NOW.plusSeconds(1));
        var authorized = commands.evaluateItem(tenant, submitted.items().getFirst().id(), 2, NOW.plusSeconds(2));
        currentEvaluator = (t, r, item) -> {
            var old = contextFor(t, r, item);
            return EligibilityResult.authorized(new RequestAdmissionContext(old.identityEpoch() + 1,
                    old.policyVersionId(), old.targetFingerprint(), old.riskFingerprint(), ids.nextId()));
        };
        assertThat(processor(accessIntent, NOW.plusSeconds(3)).processAvailable().processed()).isEqualTo(1);
        assertThat(requestRepository.findItem(tenant, authorized.id()).orElseThrow().state()).isEqualTo(ItemState.CANCELLED);
        assertThat(requestDerivedAssignmentCount(authorized.id())).isZero();
    }

    @Test
    void independentConcurrentAdmissionsCannotBothPassAnOverlappingConflict() throws Exception {
        var beneficiary = identity("Concurrent subject");
        var left = entitlement().entitlementId();
        var right = entitlement().entitlementId();
        var from = NOW.plusSeconds(3600);
        var until = from.plusSeconds(3600);
        var coordinator = io.wyrmgate.iam.testsupport.AdmissionTestSupport.coordinator(jdbc, transactions);
        var gate = new java.util.concurrent.CountDownLatch(1);
        java.util.function.BiFunction<java.util.UUID,java.util.UUID,Boolean> admit = (target,counterpart) ->
                coordinator.execute(tenant, beneficiary.id(), permit -> {
                    if (conflicts.overlappingEntitlementIds(tenant, beneficiary.id(), java.util.Set.of(counterpart), from, until).contains(counterpart)) return false;
                    var reference = new CatalogQueryService(catalogRepository).resolveActiveEntitlement(tenant,target);
                    accessIntent.applyRequestedAccess(tenant,new AccessIntentCommand.RequestedAccess(ids.nextId(),beneficiary.id(),
                            AccessIntentCommand.TargetKind.ENTITLEMENT,null,target,AccessIntentCommand.PrincipalConstraintKind.ANY,null,from,until),
                            new AccessIntentCommand.Admission(identityReferences.accessStatus(tenant,beneficiary.id()).accessEligibilityEpoch(),
                                    io.wyrmgate.iam.catalog.application.AccessTargetFingerprint.entitlement(target,reference.applicationTargetId())), NOW);
                    return true;
                });
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { gate.await(); return admit.apply(left,right); });
            var second = workers.submit(() -> { gate.await(); return admit.apply(right,left); });
            gate.countDown();
            assertThat(java.util.List.of(first.get(15,java.util.concurrent.TimeUnit.SECONDS),second.get(15,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        assertThat(conflicts.overlappingEntitlementIds(tenant,beneficiary.id(),java.util.Set.of(left,right),from,until)).hasSize(1);
        assertThat(conflicts.overlappingEntitlementIds(tenant,beneficiary.id(),java.util.Set.of(left,right),until,until.plusSeconds(1))).isEmpty();
        assertThat(conflicts.overlappingEntitlementIds(tenant,beneficiary.id(),java.util.Set.of(left,right),NOW,from)).isEmpty();
    }

    private RequestAdmissionContext contextFor(TenantContext t, AccessRequestModels.AccessRequest request,
                                               AccessRequestModels.RequestItem item) {
        var ref = new CatalogQueryService(catalogRepository).resolveActiveEntitlement(t, item.entitlementId());
        return new RequestAdmissionContext(
                identityReferences.accessStatus(t, request.beneficiaryIdentityId()).accessEligibilityEpoch(),
                new java.util.UUID(0, 1),
                io.wyrmgate.iam.catalog.application.AccessTargetFingerprint.entitlement(item.entitlementId(), ref.applicationTargetId()),
                "sha256:" + "b".repeat(64), ids.nextId());
    }

    private AccessIntentCommand.Admission admissionFor(AccessRequestModels.RequestItem item) {
        var context = requestRepository.findAdmissionContext(tenant, item.id(), item.revision() - 1).orElseThrow();
        return new AccessIntentCommand.Admission(context.identityEpoch(), context.targetFingerprint());
    }

    private AccessRequestCommandService requestService(
            AccessRequestEligibilityEvaluator evaluator) {
        currentEvaluator = evaluator;
        return new AccessRequestCommandService(
                        requestRepository,
                        evaluator,
                        new io.wyrmgate.iam.governance.application.RequestAdmissionDecisionService(requestRepository, approvalRepository, new io.wyrmgate.iam.governance.application.ApprovalCaseStartService(approvalRepository, ids, transactions), authorizedAccess),
                        new io.wyrmgate.iam.governance.persistence.JdbcSubmittedRequestItemSink(new io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository(jdbc), ids),
                        ids, transactions);
    }

    private AccessRequestAccessApplicationService processor(
            AccessIntentCommand command,
            Instant now) {
        return new AccessRequestAccessApplicationService(
                outbox,
                requestRepository,
                command, accessIntent, currentEvaluator,
                new io.wyrmgate.iam.governance.application.RequestAdmissionDecisionService(requestRepository, approvalRepository, new io.wyrmgate.iam.governance.application.ApprovalCaseStartService(approvalRepository, ids, transactions), authorizedAccess),
                transactions, io.wyrmgate.iam.testsupport.AdmissionTestSupport.coordinator(jdbc, transactions),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private AccessIntentCommand.RequestedAccess requestedAccess(
            AccessRequestModels.RequestItem item,
            java.util.UUID beneficiaryId) {
        return new AccessIntentCommand.RequestedAccess(
                item.id(),
                beneficiaryId,
                item.targetKind() == TargetKind.ROLE
                        ? AccessIntentCommand.TargetKind.ROLE
                        : AccessIntentCommand.TargetKind.ENTITLEMENT,
                item.roleId(),
                item.entitlementId(),
                item.principalConstraintKind()
                        == PrincipalConstraintKind.ANY
                        ? AccessIntentCommand.PrincipalConstraintKind.ANY
                        : AccessIntentCommand.PrincipalConstraintKind.SPECIFIC,
                item.specificPrincipalId(),
                item.validFrom(),
                item.validUntil());
    }

    private int requestDerivedAssignmentCount(
            java.util.UUID requestItemId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM access.access_assignment
                WHERE tenant_id = ?
                  AND provenance_kind = 'REQUEST_ITEM'
                  AND provenance_ref_id = ?
                """,
                Integer.class,
                tenant.tenantId(),
                requestItemId);
        return count == null ? 0 : count;
    }

    private Identity identity(String name) {
        return identities.create(
                tenant,
                IdentityType.PERSON,
                new IdentityProfile.PersonProfile(),
                IdentityLifecycleState.ACTIVE,
                name,
                NOW,
                ids.nextId(),
                null);
    }

    private TargetEntitlement entitlement() {
        String suffix = ids.nextId().toString()
                .substring(24);
        var application = catalog.createApplication(
                tenant,
                "app-" + suffix,
                "App " + suffix,
                NOW);
        var target = catalog.createTarget(
                tenant,
                application.id(),
                "prod",
                NOW);
        var entitlement = catalog.createEntitlement(tenant, application.id(), target.id(), "read-" + suffix, NOW);
        return new TargetEntitlement(
                target.id(), entitlement.id());
    }

    private record TargetEntitlement(
            java.util.UUID targetId,
            java.util.UUID entitlementId) {}
}
