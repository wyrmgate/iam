package io.wyrmgate.iam.governance.application;

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
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class AccessRequestAccessApplicationIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-28T10:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
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
    private static AccessIntentCommand accessIntent;

    private TenantContext tenant;
    private JdbcAccessRequestRepository requestRepository;
    private JdbcApprovalRepository approvalRepository;
    private AuthorizedAccessIntentSink authorizedAccess;
    private ApprovalCommandService approvals;

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
                .isEqualTo("51");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        outbox = new JdbcOutboxRepository(jdbc);
        scheduledWork = new JdbcScheduledWorkRepository(jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));

        identityRepository = new JdbcIdentityRepository(jdbc);
        identities = new IdentityCommandService(
                identityRepository,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);
        principalRepository = new JdbcPrincipalRepository(jdbc);
        identityReferences =
                new IdentityAccessReferenceQueryService(
                        identityRepository,
                        principalRepository);

        catalogRepository = new JdbcCatalogRepository(jdbc);
        catalog = new CatalogCommandService(
                catalogRepository, ids, transactions);
        roleExpansion = new RoleExpansionQueryService(
                catalogRepository,
                new JdbcRoleRepository(jdbc));

        principals = new PrincipalCommandService(
                principalRepository,
                identityRepository,
                new CatalogQueryService(catalogRepository),
                new JdbcPrincipalFactSink(outbox, ids),
                ids,
                transactions);

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
                        roleExpansion,
                        facts,
                        boundaries,
                        ids,
                        transactions);
        accessIntent = new AccessIntentCommandService(
                assignmentRepository, assignmentCommands);
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
                tenants.create("Request application", NOW).id());
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
                        authorizedAccess),
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
                        EligibilityResult.authorized());
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
                        EligibilityResult.approvalRequired(
                                new PlanSpec(List.of(
                                        new StageSpec(
                                                DecisionMode.ANY_ONE,
                                                List.of(
                                                        approver.id()))))));
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
        AccessIntentCommand flaky = (requestedTenant, request, now) -> {
            if (failFirst.getAndSet(false)) {
                throw new IllegalStateException(
                        "temporary Access failure");
            }
            return accessIntent.applyRequestedAccess(
                    requestedTenant, request, now);
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
                        NOW.plusSeconds(11));
        assertThat(replay.accessAssignmentId())
                .isEqualTo(applied.accessAssignmentId());
        assertThat(requestDerivedAssignmentCount(
                authorized.id())).isEqualTo(1);
    }

    private AccessRequestCommandService requestService(
            AccessRequestEligibilityEvaluator evaluator) {
        return new AccessRequestCommandService(
                requestRepository,
                evaluator,
                approvals,
                authorizedAccess,
                ids,
                transactions);
    }

    private AccessRequestAccessApplicationService processor(
            AccessIntentCommand command,
            Instant now) {
        return new AccessRequestAccessApplicationService(
                outbox,
                requestRepository,
                command,
                transactions,
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
                .substring(0, 8);
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
        var entitlement = catalog.createEntitlement(
                tenant,
                application.id(),
                target.id(),
                "read-" + suffix,
                "Read",
                "GROUP",
                NOW);
        return new TargetEntitlement(
                target.id(), entitlement.id());
    }

    private record TargetEntitlement(
            java.util.UUID targetId,
            java.util.UUID entitlementId) {}
}
