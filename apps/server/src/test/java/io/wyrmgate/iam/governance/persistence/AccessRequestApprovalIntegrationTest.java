package io.wyrmgate.iam.governance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.wyrmgate.iam.access.application.AccessAssignmentBoundaryScheduler;
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
import io.wyrmgate.iam.governance.application.AccessRequestApprovalProcessingService;
import io.wyrmgate.iam.governance.application.AccessRequestApprovalRequirementsResolver;
import io.wyrmgate.iam.governance.application.AccessRequestEligibilityEvaluator;
import io.wyrmgate.iam.governance.application.AccessRequestService;
import io.wyrmgate.iam.governance.application.ApprovalService;
import io.wyrmgate.iam.governance.application.FailClosedApprovalRequirementsResolver;
import io.wyrmgate.iam.governance.application.StructuralAccessRequestEligibilityEvaluator;
import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.governance.domain.ApprovalPlan;
import io.wyrmgate.iam.governance.domain.ApprovalStage;
import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQueryService;
import io.wyrmgate.iam.identity.application.IdentityApprovalActorEligibilityQuery;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.application.IdentityFactSink;
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

class AccessRequestApprovalIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-28T08:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;
    private static JdbcOutboxRepository outbox;
    private static JdbcScheduledWorkRepository scheduledWork;

    private static JdbcIdentityRepository identityRepository;
    private static IdentityCommandService identities;
    private static IdentityAccessReferenceQueryService identityReferences;
    private static JdbcCatalogRepository catalogRepository;
    private static CatalogCommandService catalog;
    private static RoleExpansionQueryService roleExpansion;
    private static JdbcAccessAssignmentRepository assignmentRepository;
    private static AccessAssignmentCommandService assignmentCommands;
    private static AccessIntentCommand accessIntent;
    private static JdbcApprovalRepository approvalRepository;
    private static JdbcAccessRequestRepository requestRepository;

    private TenantContext tenant;
    private ApprovalService approvals;
    private MutableResolver resolver;
    private AccessRequestService requestService;
    private AccessRequestApprovalProcessingService processor;

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
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        outbox = new JdbcOutboxRepository(jdbc);
        scheduledWork = new JdbcScheduledWorkRepository(jdbc, ids);

        identityRepository = new JdbcIdentityRepository(jdbc);
        IdentityFactSink noIdentityFacts = new IdentityFactSink() {
            @Override
            public void identityCreated(
                    TenantContext tenant,
                    Identity identity,
                    UUID correlationId,
                    UUID causationId) {
            }

            @Override
            public void displayNameChanged(
                    TenantContext tenant,
                    Identity identity,
                    UUID correlationId,
                    UUID causationId) {
            }
        };
        identities = new IdentityCommandService(
                identityRepository,
                noIdentityFacts,
                ids,
                transactions);
        identityReferences =
                new IdentityAccessReferenceQueryService(
                        identityRepository,
                        new JdbcPrincipalRepository(jdbc));

        catalogRepository = new JdbcCatalogRepository(jdbc);
        catalog = new CatalogCommandService(
                catalogRepository, ids, transactions);
        JdbcRoleRepository roleRepository =
                new JdbcRoleRepository(jdbc);
        roleExpansion = new RoleExpansionQueryService(
                catalogRepository, roleRepository);

        assignmentRepository =
                new JdbcAccessAssignmentRepository(jdbc);
        AccessAssignmentFactSink facts =
                new JdbcAccessAssignmentFactSink(
                        outbox, ids);
        AccessAssignmentBoundaryScheduler boundaries =
                new JdbcAccessAssignmentBoundaryScheduler(
                        scheduledWork);
        assignmentCommands =
                new AccessAssignmentCommandService(
                        assignmentRepository,
                        identityReferences,
                        new CatalogQueryService(
                                catalogRepository),
                        roleExpansion,
                        facts,
                        boundaries,
                        ids,
                        transactions);
        accessIntent = new AccessIntentCommandService(
                assignmentRepository, assignmentCommands);

        approvalRepository =
                new JdbcApprovalRepository(jdbc);
        requestRepository =
                new JdbcAccessRequestRepository(jdbc);
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
                    governance.request_item,
                    governance.access_request,
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
                    platform.tenant
                CASCADE
                """);

        tenant = new TenantContext(
                tenants.create("Request Approval", NOW).id());
        approvals = new ApprovalService(
                approvalRepository,
                new JdbcApprovalOutcomeSink(outbox, ids),
                new IdentityApprovalActorEligibilityQuery(
                        identityRepository),
                ids,
                transactions);
        resolver = new MutableResolver();
        AccessRequestEligibilityEvaluator eligibility =
                new StructuralAccessRequestEligibilityEvaluator(
                        identityReferences,
                        new CatalogQueryService(catalogRepository),
                        roleExpansion);
        requestService = new AccessRequestService(
                requestRepository,
                approvalRepository,
                approvals,
                eligibility,
                resolver,
                identityReferences,
                accessIntent,
                ids,
                transactions);
        processor =
                new AccessRequestApprovalProcessingService(
                        outbox,
                        approvalRepository,
                        requestRepository,
                        requestService,
                        transactions);
    }

    @Test
    void approvedRequestCreatesOneRequestDerivedAssignmentAndCompletes() {
        Identity requester = identity("Requester");
        Identity beneficiary = identity("Beneficiary");
        Identity approver = identity("Approver");
        UUID entitlementId = entitlement();

        resolver.require(approver.id());
        var request = requestService.create(
                tenant,
                requester.id(),
                beneficiary.id(),
                List.of(entitlementItem(entitlementId)),
                NOW);
        requestService.submit(
                tenant, request.id(), 1, NOW.plusSeconds(1));

        RequestItem item = requestRepository.findItems(
                        tenant, request.id())
                .getFirst();
        assertThat(item.lifecycleState())
                .isEqualTo(
                        RequestItem.LifecycleState.PENDING_APPROVAL);
        ApprovalPlan plan = approvalRepository.findPlan(
                        tenant, item.approvalPlanId())
                .orElseThrow();

        approvals.decide(
                tenant,
                plan.id(),
                approver.id(),
                ApprovalDecision.Value.APPROVE,
                "approved",
                plan.revision(),
                ids.nextId(),
                null,
                NOW.plusSeconds(2));

        assertThat(processor.processAvailable()).isEqualTo(1);

        RequestItem applied =
                requestRepository.findItem(
                        tenant, item.id())
                .orElseThrow();
        assertThat(applied.lifecycleState())
                .isEqualTo(RequestItem.LifecycleState.APPLIED);
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
                .isEqualTo(item.id());
        assertThat(assignment.identityId())
                .isEqualTo(beneficiary.id());
        assertThat(assignment.entitlementId())
                .isEqualTo(entitlementId);

        assertThat(requestRepository.findRequest(
                        tenant, request.id())
                .orElseThrow()
                .lifecycleState())
                .isEqualTo(
                        io.wyrmgate.iam.governance.domain.AccessRequest
                                .LifecycleState.COMPLETED);

        AccessAssignment replay =
                accessIntent.applyRequestedAccess(
                        tenant,
                        new AccessIntentCommand.RequestedAccess(
                                item.id(),
                                beneficiary.id(),
                                AccessIntentCommand.TargetKind.ENTITLEMENT,
                                null,
                                entitlementId,
                                AccessAssignment.PrincipalConstraintKind.ANY,
                                null,
                                null,
                                null),
                        NOW.plusSeconds(3));
        assertThat(replay.id()).isEqualTo(assignment.id());
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM access.access_assignment
                WHERE tenant_id = ?
                  AND provenance_kind = 'REQUEST_ITEM'
                  AND provenance_ref_id = ?
                """,
                Integer.class,
                tenant.tenantId(),
                item.id()))
                .isEqualTo(1);
    }

    @Test
    void unavailableApprovalRequirementsFailClosedInEvaluation() {
        Identity requester = identity("Requester");
        Identity beneficiary = identity("Beneficiary");
        UUID entitlementId = entitlement();

        AccessRequestService failClosed =
                new AccessRequestService(
                        requestRepository,
                        approvalRepository,
                        approvals,
                        new StructuralAccessRequestEligibilityEvaluator(
                                identityReferences,
                                new CatalogQueryService(
                                        catalogRepository),
                                roleExpansion),
                        new FailClosedApprovalRequirementsResolver(),
                        identityReferences,
                        accessIntent,
                        ids,
                        transactions);

        var request = failClosed.create(
                tenant,
                requester.id(),
                beneficiary.id(),
                List.of(entitlementItem(entitlementId)),
                NOW);
        failClosed.submit(
                tenant, request.id(), 1, NOW.plusSeconds(1));

        RequestItem item = requestRepository.findItems(
                        tenant, request.id())
                .getFirst();
        assertThat(item.lifecycleState())
                .isEqualTo(RequestItem.LifecycleState.EVALUATING);
        assertThat(item.approvalPlanId()).isNull();
        assertThat(assignmentRepository.findByProvenance(
                tenant,
                AccessAssignment.ProvenanceKind.REQUEST_ITEM,
                item.id())).isEmpty();
    }

    @Test
    void changedApprovalRequirementsCreateSuccessorPlanBeforeAccess() {
        Identity requester = identity("Requester");
        Identity beneficiary = identity("Beneficiary");
        Identity firstApprover = identity("First Approver");
        Identity replacementApprover =
                identity("Replacement Approver");
        UUID entitlementId = entitlement();

        resolver.require(firstApprover.id());
        var request = requestService.create(
                tenant,
                requester.id(),
                beneficiary.id(),
                List.of(entitlementItem(entitlementId)),
                NOW);
        requestService.submit(
                tenant, request.id(), 1, NOW.plusSeconds(1));
        RequestItem original = requestRepository.findItems(
                        tenant, request.id())
                .getFirst();
        UUID originalPlanId = original.approvalPlanId();

        ApprovalPlan originalPlan =
                approvalRepository.findPlan(
                        tenant, originalPlanId)
                .orElseThrow();
        approvals.decide(
                tenant,
                originalPlan.id(),
                firstApprover.id(),
                ApprovalDecision.Value.APPROVE,
                null,
                originalPlan.revision(),
                ids.nextId(),
                null,
                NOW.plusSeconds(2));

        resolver.require(replacementApprover.id());
        assertThat(processor.processAvailable()).isEqualTo(1);

        RequestItem reevaluated =
                requestRepository.findItem(
                        tenant, original.id())
                .orElseThrow();
        assertThat(reevaluated.lifecycleState())
                .isEqualTo(
                        RequestItem.LifecycleState.PENDING_APPROVAL);
        assertThat(reevaluated.approvalPlanId())
                .isNotEqualTo(originalPlanId);
        assertThat(assignmentRepository.findByProvenance(
                tenant,
                AccessAssignment.ProvenanceKind.REQUEST_ITEM,
                original.id())).isEmpty();

        ApprovalPlan replacement =
                approvalRepository.findPlan(
                        tenant,
                        reevaluated.approvalPlanId())
                .orElseThrow();
        approvals.decide(
                tenant,
                replacement.id(),
                replacementApprover.id(),
                ApprovalDecision.Value.APPROVE,
                null,
                replacement.revision(),
                ids.nextId(),
                null,
                NOW.plusSeconds(3));

        assertThat(processor.processAvailable()).isEqualTo(1);
        assertThat(requestRepository.findItem(
                        tenant, original.id())
                .orElseThrow()
                .lifecycleState())
                .isEqualTo(RequestItem.LifecycleState.APPLIED);
    }

    @Test
    void rejectedApprovalRejectsItemWithoutCreatingAccess() {
        Identity requester = identity("Requester");
        Identity beneficiary = identity("Beneficiary");
        Identity approver = identity("Approver");
        UUID entitlementId = entitlement();

        resolver.require(approver.id());
        var request = requestService.create(
                tenant,
                requester.id(),
                beneficiary.id(),
                List.of(entitlementItem(entitlementId)),
                NOW);
        requestService.submit(
                tenant, request.id(), 1, NOW.plusSeconds(1));
        RequestItem item = requestRepository.findItems(
                        tenant, request.id())
                .getFirst();
        ApprovalPlan plan = approvalRepository.findPlan(
                        tenant, item.approvalPlanId())
                .orElseThrow();

        approvals.decide(
                tenant,
                plan.id(),
                approver.id(),
                ApprovalDecision.Value.REJECT,
                "not justified",
                plan.revision(),
                ids.nextId(),
                null,
                NOW.plusSeconds(2));
        assertThat(processor.processAvailable()).isEqualTo(1);

        assertThat(requestRepository.findItem(
                        tenant, item.id())
                .orElseThrow()
                .lifecycleState())
                .isEqualTo(RequestItem.LifecycleState.REJECTED);
        assertThat(assignmentRepository.findByProvenance(
                tenant,
                AccessAssignment.ProvenanceKind.REQUEST_ITEM,
                item.id())).isEmpty();
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

    private UUID entitlement() {
        String suffix = ids.nextId().toString()
                .substring(0, 8);
        var app = catalog.createApplication(
                tenant,
                "app-" + suffix,
                "App " + suffix,
                NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        return catalog.createEntitlement(
                        tenant,
                        app.id(),
                        target.id(),
                        "read-" + suffix,
                        "Read",
                        "GROUP",
                        NOW)
                .id();
    }

    private AccessRequestService.ItemSpec entitlementItem(
            UUID entitlementId) {
        return new AccessRequestService.ItemSpec(
                RequestItem.TargetKind.ENTITLEMENT,
                null,
                entitlementId,
                RequestItem.PrincipalConstraintKind.ANY,
                null,
                null,
                null);
    }

    private static final class MutableResolver
            implements AccessRequestApprovalRequirementsResolver {

        private UUID approverId;

        void require(UUID approverId) {
            this.approverId = approverId;
        }

        @Override
        public Resolution resolve(
                TenantContext tenant,
                RequestItem item,
                Instant now) {
            return Resolution.approvalRequired(
                    "policy:" + approverId,
                    List.of(new ApprovalService.StageSpec(
                            ApprovalStage.DecisionMode.ANY_ONE,
                            List.of(approverId))),
                    null);
        }
    }
}
