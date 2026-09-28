package io.wyrmgate.iam.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.AccessAssignmentCommandService;
import io.wyrmgate.iam.access.application.AccessIntentCommandService;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentRepository;
import io.wyrmgate.iam.catalog.application.CatalogCommandService;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.persistence.JdbcCatalogRepository;
import io.wyrmgate.iam.governance.domain.AccessRequest;
import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.governance.persistence.JdbcAccessRequestRepository;
import io.wyrmgate.iam.governance.persistence.JdbcApprovalOutcomeFactSink;
import io.wyrmgate.iam.governance.persistence.JdbcApprovalRepository;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQueryService;
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
            Instant.parse("2026-09-28T05:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;
    private static JdbcOutboxRepository outbox;
    private static IdentityCommandService identities;
    private static CatalogCommandService catalog;
    private static IdentityAccessReferenceQueryService identityReferences;
    private static JdbcAccessAssignmentRepository assignmentRepository;
    private static AccessIntentCommandService accessIntent;
    private static JdbcApprovalRepository approvalRepository;
    private static ApprovalService approvals;
    private static JdbcAccessRequestRepository requestRepository;

    private TenantContext tenant;
    private Identity requester;
    private Identity beneficiary;
    private Identity approver;

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
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        outbox = new JdbcOutboxRepository(jdbc);

        var identityRepository = new JdbcIdentityRepository(jdbc);
        var principalRepository = new JdbcPrincipalRepository(jdbc);
        identityReferences = new IdentityAccessReferenceQueryService(
                identityRepository, principalRepository);
        identities = new IdentityCommandService(
                identityRepository,
                noIdentityFacts(),
                ids,
                transactions);

        var catalogRepository = new JdbcCatalogRepository(jdbc);
        catalog = new CatalogCommandService(
                catalogRepository, ids, transactions);

        assignmentRepository =
                new JdbcAccessAssignmentRepository(jdbc);
        AccessAssignmentCommandService assignmentCommands =
                new AccessAssignmentCommandService(
                        assignmentRepository,
                        identityReferences,
                        new CatalogQueryService(catalogRepository),
                        (tenant, assignment) -> { },
                        (tenant, assignment, now) -> { },
                        ids,
                        transactions);
        accessIntent = new AccessIntentCommandService(
                assignmentCommands);

        approvalRepository = new JdbcApprovalRepository(jdbc);
        approvals = new ApprovalService(
                approvalRepository,
                new JdbcApprovalOutcomeFactSink(outbox, ids),
                ids,
                transactions);
        requestRepository =
                new JdbcAccessRequestRepository(jdbc);
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
                    governance.request_item,
                    governance.access_request,
                    governance.approval_case,
                    access.access_assignment,
                    platform.outbox_event,
                    identity.principal,
                    identity.person_profile,
                    identity.service_profile,
                    identity.workload_profile,
                    identity.identity,
                    catalog.entitlement,
                    catalog.application_target,
                    catalog.application,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create("Request Approval", NOW).id());
        requester = identity("Requester");
        beneficiary = identity("Beneficiary");
        approver = identity("Approver");
    }

    @Test
    void approvedRequestCreatesExactlyOneRequestDerivedAssignment() {
        var app = catalog.createApplication(
                tenant, "finance", "Finance", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant,
                app.id(),
                target.id(),
                "read",
                "Read",
                "GROUP",
                NOW);

        AccessRequestService service = requestService(
                (tenant, request, item) ->
                        AccessRequestEligibilityEvaluator.Evaluation
                                .eligible(oneApprover(approver.id())));

        AccessRequest request = service.createDraft(
                tenant,
                requester.id(),
                beneficiary.id(),
                List.of(new AccessRequestService.ItemSpec(
                        RequestItem.TargetKind.ENTITLEMENT,
                        null,
                        entitlement.id(),
                        RequestItem.PrincipalConstraintKind.ANY,
                        null,
                        null,
                        null)),
                NOW);

        service.submit(
                tenant,
                request.id(),
                request.revision(),
                NOW.plusSeconds(1),
                ids.nextId());

        RequestItem pending =
                requestRepository.findItems(
                        tenant, request.id()).getFirst();
        assertThat(pending.lifecycleState())
                .isEqualTo(
                        RequestItem.LifecycleState.PENDING_APPROVAL);
        assertThat(pending.approvalCaseId()).isNotNull();

        ApprovalCase approval = approvalRepository.findCase(
                        tenant, pending.approvalCaseId())
                .orElseThrow();
        ApprovalCase approved = approvals.decide(
                tenant,
                approval.id(),
                approver.id(),
                ApprovalDecision.Decision.APPROVE,
                approval.revision(),
                NOW.plusSeconds(2),
                ids.nextId(),
                null);
        assertThat(approved.lifecycleState())
                .isEqualTo(ApprovalCase.LifecycleState.APPROVED);

        var processor = new AccessRequestApprovalOutcomeProcessingService(
                outbox,
                service,
                new ObjectMapper(),
                java.time.Clock.fixed(
                        NOW.plusSeconds(10),
                        java.time.ZoneOffset.UTC));
        var result = processor.processAvailable();
        assertThat(result.processed()).isEqualTo(1);

        RequestItem applied = requestRepository.findItem(
                        tenant, pending.id())
                .orElseThrow();
        assertThat(applied.lifecycleState())
                .isEqualTo(RequestItem.LifecycleState.APPLIED);
        assertThat(applied.accessAssignmentId()).isNotNull();

        var assignment = assignmentRepository.findById(
                        tenant, applied.accessAssignmentId())
                .orElseThrow();
        assertThat(assignment.identityId())
                .isEqualTo(beneficiary.id());
        assertThat(assignment.entitlementId())
                .isEqualTo(entitlement.id());
        assertThat(assignment.provenanceKind())
                .isEqualTo(
                        io.wyrmgate.iam.access.domain.AccessAssignment
                                .ProvenanceKind.APPROVED_REQUEST);
        assertThat(assignment.provenanceRefId())
                .isEqualTo(applied.id());

        assertThat(assignmentRepository.findByProvenance(
                tenant,
                io.wyrmgate.iam.access.domain.AccessAssignment
                        .ProvenanceKind.APPROVED_REQUEST,
                applied.id()))
                .contains(assignment);

        AccessRequest completed = requestRepository.findRequest(
                        tenant, request.id())
                .orElseThrow();
        assertThat(completed.lifecycleState())
                .isEqualTo(AccessRequest.LifecycleState.COMPLETED);

        service.applyApprovalOutcome(
                tenant,
                approved.id(),
                applied.id(),
                pending.revision(),
                ApprovalCase.LifecycleState.APPROVED,
                NOW.plusSeconds(3));

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM access.access_assignment
                WHERE tenant_id = ?
                  AND provenance_kind = 'APPROVED_REQUEST'
                  AND provenance_ref_id = ?
                """,
                Integer.class,
                tenant.tenantId(),
                applied.id()))
                .isEqualTo(1);
    }

    @Test
    void unavailableMandatoryEvaluatorFailsClosedBeforeApproval() {
        var app = catalog.createApplication(
                tenant, "finance", "Finance", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant,
                app.id(),
                target.id(),
                "read",
                "Read",
                "GROUP",
                NOW);

        AccessRequestService service = requestService(
                new FailClosedAccessRequestEligibilityEvaluator());

        AccessRequest request = service.createDraft(
                tenant,
                requester.id(),
                beneficiary.id(),
                List.of(new AccessRequestService.ItemSpec(
                        RequestItem.TargetKind.ENTITLEMENT,
                        null,
                        entitlement.id(),
                        RequestItem.PrincipalConstraintKind.ANY,
                        null,
                        null,
                        null)),
                NOW);

        service.submit(
                tenant,
                request.id(),
                request.revision(),
                NOW.plusSeconds(1),
                ids.nextId());

        RequestItem item = requestRepository.findItems(
                        tenant, request.id())
                .getFirst();
        assertThat(item.lifecycleState())
                .isEqualTo(RequestItem.LifecycleState.EVALUATING);
        assertThat(item.approvalCaseId()).isNull();

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE governance.request_item
                SET entitlement_id = ?
                WHERE tenant_id = ? AND id = ?
                """, ids.nextId(), tenant.tenantId(), item.id()))
                .hasMessageContaining("immutable");

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM governance.approval_case
                WHERE tenant_id = ?
                """, Integer.class, tenant.tenantId()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM access.access_assignment
                WHERE tenant_id = ?
                """, Integer.class, tenant.tenantId()))
                .isZero();
    }

    @Test
    void staleApprovalOutcomeCannotAuthorizeChangedRequestItem() {
        var app = catalog.createApplication(
                tenant, "finance", "Finance", NOW);
        var target = catalog.createTarget(
                tenant, app.id(), "prod", NOW);
        var entitlement = catalog.createEntitlement(
                tenant,
                app.id(),
                target.id(),
                "read",
                "Read",
                "GROUP",
                NOW);

        AccessRequestService service = requestService(
                (tenant, request, item) ->
                        AccessRequestEligibilityEvaluator.Evaluation
                                .eligible(oneApprover(approver.id())));
        AccessRequest request = service.createDraft(
                tenant,
                requester.id(),
                beneficiary.id(),
                List.of(new AccessRequestService.ItemSpec(
                        RequestItem.TargetKind.ENTITLEMENT,
                        null,
                        entitlement.id(),
                        RequestItem.PrincipalConstraintKind.ANY,
                        null,
                        null,
                        null)),
                NOW);
        service.submit(
                tenant,
                request.id(),
                request.revision(),
                NOW.plusSeconds(1),
                ids.nextId());

        RequestItem pending = requestRepository.findItems(
                        tenant, request.id())
                .getFirst();
        ApprovalCase approval = approvalRepository.findCase(
                        tenant, pending.approvalCaseId())
                .orElseThrow();
        approvals.decide(
                tenant,
                approval.id(),
                approver.id(),
                ApprovalDecision.Decision.APPROVE,
                approval.revision(),
                NOW.plusSeconds(2),
                ids.nextId(),
                null);

        RequestItem changed = requestRepository.updateItemState(
                tenant,
                pending.id(),
                pending.revision(),
                RequestItem.LifecycleState.PENDING_APPROVAL,
                pending.approvalCaseId(),
                null,
                NOW.plusSeconds(3),
                null);
        assertThat(changed.revision())
                .isEqualTo(pending.revision() + 1);

        var processor = new AccessRequestApprovalOutcomeProcessingService(
                outbox,
                service,
                new ObjectMapper());
        assertThat(processor.processAvailable().processed())
                .isEqualTo(1);

        RequestItem unchanged = requestRepository.findItem(
                        tenant, pending.id())
                .orElseThrow();
        assertThat(unchanged.lifecycleState())
                .isEqualTo(
                        RequestItem.LifecycleState.PENDING_APPROVAL);
        assertThat(unchanged.accessAssignmentId()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM access.access_assignment
                WHERE tenant_id = ?
                """, Integer.class, tenant.tenantId()))
                .isZero();
    }

    private AccessRequestService requestService(
            AccessRequestEligibilityEvaluator evaluator) {
        return new AccessRequestService(
                requestRepository,
                evaluator,
                approvals,
                identityReferences,
                accessIntent,
                ids,
                transactions);
    }

    private ApprovalPlanSpec oneApprover(UUID approverId) {
        return new ApprovalPlanSpec(
                ApprovalPlanSpec.SelfApprovalPolicy.DENY_REQUESTER,
                List.of(new ApprovalPlanSpec.StageSpec(
                        ApprovalPlanSpec.DecisionMode.ANY_ONE,
                        List.of(approverId))));
    }

    private Identity identity(String displayName) {
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

    private static IdentityFactSink noIdentityFacts() {
        return new IdentityFactSink() {
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
    }
}
