package io.wyrmgate.iam.api.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.audit.application.AuditCommandService;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.persistence.JdbcAuditRecordRepository;
import io.wyrmgate.iam.governance.application.AccessRequestAccessApplicationService;
import io.wyrmgate.iam.governance.application.AccessRequestApprovalResultSink;
import io.wyrmgate.iam.governance.application.AccessRequestCommandService;
import io.wyrmgate.iam.governance.application.AccessRequestEvaluationProcessingService;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityResult;
import io.wyrmgate.iam.governance.application.ApprovalCommandService;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.governance.application.ApprovalCaseStartService;
import io.wyrmgate.iam.governance.application.GovernanceExceptionService;
import io.wyrmgate.iam.governance.application.GovernancePolicyService;
import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.LifecycleState;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyDecision;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.PolicyKind;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.RiskSeverity;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDAction;
import io.wyrmgate.iam.governance.domain.GovernancePolicyModels.SoDRuleSpec;
import io.wyrmgate.iam.governance.persistence.JdbcAccessRequestRepository;
import io.wyrmgate.iam.governance.persistence.JdbcApprovalRepository;
import io.wyrmgate.iam.governance.persistence.JdbcGovernancePolicyRepository;
import io.wyrmgate.iam.governance.persistence.JdbcGovernanceExceptionRepository;
import io.wyrmgate.iam.governance.persistence.JdbcAuthorizedAccessIntentSink;
import io.wyrmgate.iam.governance.persistence.JdbcSubmittedRequestItemSink;
import io.wyrmgate.iam.platform.crypto.SigningKeyMaterial;
import io.wyrmgate.iam.platform.crypto.SigningKeyProvider;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.JdbcTenantRepository;
import io.wyrmgate.iam.platform.persistence.SpringTransactionExecutor;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;

class GovernanceApiIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final String ACTOR_ATTRIBUTE =
            "io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext.actor";

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdempotencyRepository idempotency;
    private static TransactionExecutor transactions;
    private static final ObjectMapper JSON =
            new ObjectMapper();

    private TenantContext tenant;
    private UUID requesterId;
    private UUID approverId;
    private AuthenticatedAdministrativeActor requester;
    private AuthenticatedAdministrativeActor approver;
    private JdbcAccessRequestRepository requests;
    private JdbcApprovalRepository approvalRepository;
    private AccessRequestCommandService requestCommands;
    private ApprovalCommandService approvalCommands;
    private ApprovalQueryService approvalQueries;
    private AccessRequestEvaluationProcessingService evaluator;
    private GovernancePolicyService policyService;
    private JdbcGovernancePolicyRepository policyRepository;
    private GovernanceExceptionService exceptionService;
    private JdbcGovernanceExceptionRepository exceptionRepository;
    private MockMvc mvc;

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
                .isEqualTo("60");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        outbox = new JdbcOutboxRepository(jdbc);
        idempotency =
                new JdbcIdempotencyRepository(jdbc, ids);
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
                    audit.audit_record,
                    platform.idempotency_record,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);

        tenant = new TenantContext(
                tenants.create(
                        "Governance API",
                        Instant.now()).id());
        requesterId = ids.nextId();
        approverId = ids.nextId();
        requester =
                new AuthenticatedAdministrativeActor(
                        tenant, requesterId);
        approver =
                new AuthenticatedAdministrativeActor(
                        tenant, approverId);

        requests = new JdbcAccessRequestRepository(jdbc);
        approvalRepository =
                new JdbcApprovalRepository(jdbc);
        var authorizedSink =
                new JdbcAuthorizedAccessIntentSink(
                        outbox, ids);
        approvalCommands = new ApprovalCommandService(
                approvalRepository,
                new AccessRequestApprovalResultSink(
                        requests,
                        authorizedSink),
                ids,
                transactions);
        approvalQueries =
                new ApprovalQueryService(
                        approvalRepository);

        requestCommands =
                new AccessRequestCommandService(
                        requests,
                        (requestedTenant, request, item) ->
                                EligibilityResult.approvalRequired(
                                        new PlanSpec(List.of(
                                                new StageSpec(
                                                        DecisionMode.ANY_ONE,
                                                        List.of(
                                                                approverId))))),
                        approvalCommands,
                        new JdbcSubmittedRequestItemSink(
                                outbox, ids),
                        authorizedSink,
                        ids,
                        transactions);
        evaluator =
                new AccessRequestEvaluationProcessingService(
                        outbox,
                        requests,
                        requestCommands);

        policyRepository = new JdbcGovernancePolicyRepository(jdbc);
        policyService = new GovernancePolicyService(
                policyRepository,
                (requestedTenant, entitlementId) ->
                        io.wyrmgate.iam.catalog.application.CatalogAccessReferenceQuery.EntitlementReference.valid(
                                UUID.fromString("00000000-0000-0000-0000-000000000001")),
                new io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery() {
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
                },
                ids,
                transactions);

        exceptionRepository = new JdbcGovernanceExceptionRepository(jdbc);
        exceptionService = new GovernanceExceptionService(
                exceptionRepository,
                policyRepository,
                new io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery() {
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
                },
                new ApprovalCaseStartService(approvalRepository, ids, transactions),
                (requestedTenant, exception) -> { },
                (requestedTenant, exception, scheduledAt) -> { },
                ids,
                transactions);

        mvc = mockMvc(
                new AuditCommandService(
                        new JdbcAuditRecordRepository(jdbc),
                        transactions,
                        Clock.systemUTC()));
    }

    @Test
    void requestSubmitEvaluationApprovalAndEvidenceArePublicSemanticFlow()
            throws Exception {
        String createBody = requestBody(
                requesterId,
                ids.nextId());
        String location = mvc.perform(
                        post("/api/v1/governance/access-requests")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        requester)
                                .header(
                                        "Idempotency-Key",
                                        "request-create-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                "ETag", "\"rev-1\""))
                .andExpect(
                        jsonPath("$.requesterIdentityId")
                                .value(
                                        requesterId.toString()))
                .andExpect(
                        jsonPath("$.beneficiaryIdentityId")
                                .value(
                                        requesterId.toString()))
                .andExpect(
                        jsonPath("$.state")
                                .value("DRAFT"))
                .andExpect(
                        jsonPath("$.items[0].state")
                                .value("DRAFT"))
                .andReturn()
                .getResponse()
                .getHeader("Location");
        UUID requestId = UUID.fromString(
                location.substring(
                        location.lastIndexOf('/') + 1));

        mvc.perform(
                        post("/api/v1/governance/access-requests")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        requester)
                                .header(
                                        "Idempotency-Key",
                                        "request-create-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(
                                        requestId.toString()));

        mvc.perform(
                        post("/api/v1/governance/access-requests/{id}/submit",
                                requestId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        requester)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "request-submit-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "ETag", "\"rev-2\""))
                .andExpect(
                        jsonPath("$.state")
                                .value("SUBMITTED"))
                .andExpect(
                        jsonPath("$.items[0].state")
                                .value("SUBMITTED"));

        assertThat(evaluator.processAvailable().processed())
                .isEqualTo(1);

        var item = requests.findItems(
                        tenant, requestId)
                .getFirst();
        assertThat(item.state().name())
                .isEqualTo("PENDING_APPROVAL");
        assertThat(item.approvalCaseId())
                .isNotNull();

        mvc.perform(
                        get("/api/v1/governance/request-items/{id}",
                                item.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        requester))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.state")
                                .value("PENDING_APPROVAL"))
                .andExpect(
                        jsonPath("$.approvalCaseId")
                                .value(
                                        item.approvalCaseId()
                                                .toString()));

        mvc.perform(
                        get("/api/v1/governance/approval-inbox")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        approver))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items.length()")
                                .value(1))
                .andExpect(
                        jsonPath("$.items[0].id")
                                .value(
                                        item.approvalCaseId()
                                                .toString()));

        mvc.perform(
                        get("/api/v1/governance/approvals/{id}",
                                item.approvalCaseId())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        requester))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "ETag", "\"rev-1\""))
                .andExpect(
                        jsonPath("$.stages[0].decisionMode")
                                .value("ANY_ONE"))
                .andExpect(
                        jsonPath(
                                "$.stages[0].approvers[0].approverIdentityId")
                                .value(
                                        approverId.toString()))
                .andExpect(
                        jsonPath(
                                "$.stages[0].decisions.length()")
                                .value(0));

        mvc.perform(
                        post("/api/v1/governance/approvals/{id}/approve",
                                item.approvalCaseId())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        approver)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "approval-decision-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reason\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "ETag", "\"rev-2\""))
                .andExpect(
                        jsonPath("$.state")
                                .value("APPROVED"))
                .andExpect(
                        jsonPath(
                                "$.stages[0].decisions[0].decision")
                                .value("APPROVE"))
                .andExpect(
                        jsonPath(
                                "$.stages[0].decisions[0].reason")
                                .value("approved"));

        mvc.perform(
                        post("/api/v1/governance/approvals/{id}/approve",
                                item.approvalCaseId())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        approver)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "approval-decision-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reason\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.state")
                                .value("APPROVED"));

        var authorized = requests.findItem(
                        tenant, item.id())
                .orElseThrow();
        assertThat(authorized.state().name())
                .isEqualTo("AUTHORIZED");

        mvc.perform(
                        get("/api/v1/governance/approval-inbox")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        approver))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items.length()")
                                .value(0));

        mvc.perform(
                        post("/api/v1/governance/access-requests/{id}/submit",
                                requestId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        requester)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "request-submit-stale"))
                .andExpect(
                        status().isPreconditionFailed())
                .andExpect(
                        jsonPath("$.code")
                                .value("stale_revision"));

        Integer requestCreateSuccess = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND actor_id = ?
                  AND action_type = 'access-request:create'
                  AND resource_type = 'access-request'
                  AND resource_id = ?
                  AND outcome = 'SUCCESS'
                """,
                Integer.class,
                tenant.tenantId(),
                requesterId,
                requestId);
        assertThat(requestCreateSuccess).isEqualTo(2);

        Integer requestSubmitSuccess = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND actor_id = ?
                  AND action_type = 'access-request:submit'
                  AND resource_id = ?
                  AND outcome = 'SUCCESS'
                """,
                Integer.class,
                tenant.tenantId(),
                requesterId,
                requestId);
        assertThat(requestSubmitSuccess).isEqualTo(1);

        Integer approvalSuccess = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND actor_id = ?
                  AND action_type = 'approval:approve'
                  AND resource_type = 'approval-case'
                  AND resource_id = ?
                  AND outcome = 'SUCCESS'
                """,
                Integer.class,
                tenant.tenantId(),
                approverId,
                item.approvalCaseId());
        assertThat(approvalSuccess).isEqualTo(2);

        Integer staleSubmitFailure = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND action_type = 'access-request:submit'
                  AND resource_id = ?
                  AND outcome = 'FAILURE'
                """,
                Integer.class,
                tenant.tenantId(),
                requestId);
        assertThat(staleSubmitFailure).isEqualTo(1);

        Integer materializedPayloads = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND resource_type IN ('access-request', 'approval-case')
                  AND material_snapshot IS NOT NULL
                """,
                Integer.class,
                tenant.tenantId());
        assertThat(materializedPayloads).isZero();
    }

    @Test
    void actorScopeInboxCursorAndApprovalAuthorityFailClosed()
            throws Exception {
        AuthenticatedAdministrativeActor outsider =
                new AuthenticatedAdministrativeActor(
                        tenant, ids.nextId());

        mvc.perform(
                        post("/api/v1/governance/access-requests")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        requester)
                                .header(
                                        "Idempotency-Key",
                                        "on-behalf-denied")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(requestBody(
                                        outsider.identityId(),
                                        ids.nextId())))
                .andExpect(status().isForbidden());

        UUID firstCase = createPendingRequest(
                "cursor-create-0001",
                "cursor-submit-0001");
        UUID secondCase = createPendingRequest(
                "cursor-create-0002",
                "cursor-submit-0002");

        String body = mvc.perform(
                        get("/api/v1/governance/approval-inbox")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        approver)
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items.length()")
                                .value(1))
                .andExpect(
                        jsonPath("$.nextCursor")
                                .isString())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode parsed = JSON.readTree(body);
        String cursor =
                parsed.get("nextCursor").asText();

        mvc.perform(
                        get("/api/v1/governance/approval-inbox")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        outsider)
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("validation_failed"));

        mvc.perform(
                        get("/api/v1/governance/approvals/{id}",
                                firstCase)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        outsider))
                .andExpect(status().isForbidden());

        mvc.perform(
                        post("/api/v1/governance/approvals/{id}/reject",
                                secondCase)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        outsider)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "outsider-reject-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("{\"reason\":null}"))
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "approval_actor_not_approver"));

        mvc.perform(
                        post("/api/v1/governance/approvals/{id}/reject",
                                secondCase)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        approver)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "approver-reject-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"not appropriate\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.state")
                                .value("REJECTED"));

        Integer deniedCreate = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND actor_id = ?
                  AND action_type = 'access-request:create'
                  AND resource_id IS NULL
                  AND outcome = 'DENIED'
                """,
                Integer.class,
                tenant.tenantId(),
                requesterId);
        assertThat(deniedCreate).isEqualTo(1);

        Integer deniedReject = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND actor_id = ?
                  AND action_type = 'approval:reject'
                  AND resource_id = ?
                  AND outcome = 'DENIED'
                """,
                Integer.class,
                tenant.tenantId(),
                outsider.identityId(),
                secondCase);
        assertThat(deniedReject).isEqualTo(1);

        Integer successfulReject = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM audit.audit_record
                WHERE tenant_id = ?
                  AND actor_id = ?
                  AND action_type = 'approval:reject'
                  AND resource_id = ?
                  AND outcome = 'SUCCESS'
                """,
                Integer.class,
                tenant.tenantId(),
                approverId,
                secondCase);
        assertThat(successfulReject).isEqualTo(1);
    }

    @Test
    void accessRequestPolicyLifecycleIsPublicAndRevisionGuarded()
            throws Exception {
        String body = """
                {
                  "defaultDecision":"AUTHORIZE",
                  "rules":[],
                  "approvalPlan":null
                }
                """;

        String createdJson = mvc.perform(
                        post("/api/v1/governance/policies/access-request/versions")
                                .requestAttr(ACTOR_ATTRIBUTE, requester)
                                .header("Idempotency-Key", "governance-policy-create-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"rev-1\""))
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.defaultDecision").value("AUTHORIZE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID versionId = UUID.fromString(JSON.readTree(createdJson).get("id").asText());

        mvc.perform(
                        post("/api/v1/governance/policies/access-request/versions")
                                .requestAttr(ACTOR_ATTRIBUTE, requester)
                                .header("Idempotency-Key", "governance-policy-create-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(versionId.toString()));

        mvc.perform(
                        post("/api/v1/governance/policies/access-request/versions/{id}:ready", versionId)
                                .requestAttr(ACTOR_ATTRIBUTE, requester)
                                .header("If-Match", "\"rev-1\"")
                                .header("Idempotency-Key", "governance-policy-ready-0001"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"rev-2\""))
                .andExpect(jsonPath("$.state").value("READY"));

        mvc.perform(
                        post("/api/v1/governance/policies/access-request/versions/{id}:activate", versionId)
                                .requestAttr(ACTOR_ATTRIBUTE, requester)
                                .header("If-Match", "\"rev-2\"")
                                .header("Idempotency-Key", "governance-policy-activate-0001"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"rev-3\""))
                .andExpect(jsonPath("$.state").value("ACTIVE"));

        mvc.perform(
                        get("/api/v1/governance/policies/access-request/active")
                                .requestAttr(ACTOR_ATTRIBUTE, requester))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(versionId.toString()))
                .andExpect(jsonPath("$.state").value("ACTIVE"));

        mvc.perform(
                        get("/api/v1/governance/policies/access-request/versions/{id}", versionId)
                                .requestAttr(ACTOR_ATTRIBUTE, requester))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3));

        mockMvc(
                new AuditCommandService(
                        new JdbcAuditRecordRepository(jdbc),
                        transactions,
                        Clock.systemUTC()),
                policyAuthorization(false))
                .perform(get("/api/v1/governance/policies/access-request/active")
                        .requestAttr(ACTOR_ATTRIBUTE, requester))
                .andExpect(status().isForbidden());
    }

    @Test
    void governanceExceptionApiBindsActiveRuleAndRevokesExplicitly()
            throws Exception {
        Instant base = Instant.now().plusSeconds(60);
        UUID left = ids.nextId();
        UUID right = ids.nextId();

        var draft = policyService.createDraft(
                tenant,
                PolicyKind.ACCESS_REQUEST,
                PolicyDecision.AUTHORIZE,
                List.of(new SoDRuleSpec(
                        "API-SOD-1",
                        left,
                        right,
                        RiskSeverity.HIGH,
                        SoDAction.REQUIRE_APPROVAL)),
                new PlanSpec(List.of(new StageSpec(
                        DecisionMode.ANY_ONE,
                        List.of(approverId)))),
                base);
        var ready = policyService.markReady(
                tenant, draft.id(), draft.revision(), base.plusSeconds(1));
        policyService.activate(
                tenant, ready.id(), ready.revision(), base.plusSeconds(2));
        UUID ruleId = policyRepository.findRules(tenant, draft.id())
                .getFirst()
                .id();

        String body = """
                {
                  "subjectIdentityId":"%s",
                  "sodRuleId":"%s",
                  "businessReason":"Temporary approved business need",
                  "validFrom":"%s",
                  "validUntil":"%s",
                  "predecessorExceptionId":null,
                  "approvalPlan":{
                    "stages":[{
                      "decisionMode":"ANY_ONE",
                      "approverIdentityIds":["%s"]
                    }]
                  }
                }
                """.formatted(
                requesterId,
                ruleId,
                base.plusSeconds(10),
                base.plusSeconds(3600),
                approverId);

        String created = mvc.perform(
                        post("/api/v1/governance/exceptions")
                                .requestAttr(ACTOR_ATTRIBUTE, requester)
                                .header("Idempotency-Key", "governance-exception-create-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"rev-1\""))
                .andExpect(jsonPath("$.scopeKind").value("IDENTITY_SOD_RULE"))
                .andExpect(jsonPath("$.lifecycleState").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.sodRuleId").value(ruleId.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID exceptionId = UUID.fromString(JSON.readTree(created).get("id").asText());

        mvc.perform(
                        post("/api/v1/governance/exceptions")
                                .requestAttr(ACTOR_ATTRIBUTE, requester)
                                .header("Idempotency-Key", "governance-exception-create-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(exceptionId.toString()));

        mvc.perform(
                        get("/api/v1/governance/exceptions/{id}", exceptionId)
                                .requestAttr(ACTOR_ATTRIBUTE, requester))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessReason")
                        .value("Temporary approved business need"));

        var current = exceptionRepository.findById(tenant, exceptionId).orElseThrow();
        var approved = exceptionRepository.updateState(
                tenant,
                exceptionId,
                LifecycleState.APPROVED,
                current.revision(),
                base.plusSeconds(5),
                base.plusSeconds(5),
                null,
                null,
                null);

        mvc.perform(
                        post("/api/v1/governance/exceptions/{id}:revoke", exceptionId)
                                .requestAttr(ACTOR_ATTRIBUTE, requester)
                                .header("If-Match", "\"rev-" + approved.revision() + "\"")
                                .header("Idempotency-Key", "governance-exception-revoke-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("REVOKED"))
                .andExpect(jsonPath("$.revision").value(approved.revision() + 1));

        mockMvc(
                new AuditCommandService(
                        new JdbcAuditRecordRepository(jdbc),
                        transactions,
                        Clock.systemUTC()),
                policyAuthorization(false))
                .perform(get("/api/v1/governance/exceptions/{id}", exceptionId)
                        .requestAttr(ACTOR_ATTRIBUTE, requester))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditFailureDoesNotRewriteSuccessfulRequestOutcome()
            throws Exception {
        SecurityAuditPort unavailableAudit =
                (requestedTenant, draft) -> {
                    throw new IllegalStateException(
                            "audit unavailable");
                };
        MockMvc unavailable = mockMvc(unavailableAudit);

        String location = unavailable.perform(
                        post("/api/v1/governance/access-requests")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        requester)
                                .header(
                                        "Idempotency-Key",
                                        "governance-audit-unavailable")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(requestBody(
                                        requesterId,
                                        ids.nextId())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        UUID requestId = UUID.fromString(
                location.substring(location.lastIndexOf('/') + 1));
        assertThat(requests.findRequest(tenant, requestId))
                .isPresent();
    }

    private MockMvc mockMvc(SecurityAuditPort audit) {
        return mockMvc(audit, policyAuthorization(true));
    }

    private MockMvc mockMvc(
            SecurityAuditPort audit,
            AdministrativeAuthorizationService policyAuthorization) {
        GovernanceApiMutationService mutations =
                new GovernanceApiMutationService(
                        requestCommands,
                        requests,
                        approvalCommands,
                        approvalQueries,
                        idempotency,
                        transactions,
                        audit,
                        ids);
        GovernanceController controller =
                new GovernanceController(
                        requests,
                        approvalQueries,
                        mutations,
                        ids,
                        cursorCodec());
        GovernancePolicyApiMutationService policyMutations =
                new GovernancePolicyApiMutationService(
                        policyAuthorization,
                        policyService,
                        idempotency,
                        transactions,
                        audit,
                        ids);
        GovernancePolicyController policyController =
                new GovernancePolicyController(
                        policyService,
                        policyMutations,
                        policyAuthorization,
                        ids);
        GovernanceExceptionApiMutationService exceptionMutations =
                new GovernanceExceptionApiMutationService(
                        policyAuthorization,
                        exceptionService,
                        idempotency,
                        transactions,
                        audit,
                        ids);
        GovernanceExceptionController exceptionController =
                new GovernanceExceptionController(
                        exceptionService,
                        exceptionMutations,
                        policyAuthorization,
                        ids);
        return MockMvcBuilders
                .standaloneSetup(controller, policyController, exceptionController)
                .setControllerAdvice(
                        new GovernanceApiErrorHandler(ids))
                .build();
    }

    private UUID createPendingRequest(
            String createKey,
            String submitKey) throws Exception {
        String location = mvc.perform(
                        post("/api/v1/governance/access-requests")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        requester)
                                .header(
                                        "Idempotency-Key",
                                        createKey)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(requestBody(
                                        requesterId,
                                        ids.nextId())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        UUID requestId = UUID.fromString(
                location.substring(
                        location.lastIndexOf('/') + 1));
        mvc.perform(
                        post("/api/v1/governance/access-requests/{id}/submit",
                                requestId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        requester)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        submitKey))
                .andExpect(status().isOk());
        assertThat(evaluator.processAvailable().processed())
                .isEqualTo(1);
        return requests.findItems(
                        tenant, requestId)
                .getFirst()
                .approvalCaseId();
    }

    private static String requestBody(
            UUID beneficiaryId,
            UUID entitlementId) {
        return """
                {
                  "beneficiaryIdentityId":"%s",
                  "items":[
                    {
                      "targetKind":"ENTITLEMENT",
                      "targetId":"%s",
                      "principalConstraintKind":"ANY",
                      "specificPrincipalId":null,
                      "validFrom":null,
                      "validUntil":null
                    }
                  ]
                }
                """.formatted(
                beneficiaryId,
                entitlementId);
    }

    private AdministrativeAuthorizationService policyAuthorization(boolean allow) {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        AdministrativeGrant grant = new AdministrativeGrant(
                ids.nextId(),
                requesterId,
                ids.nextId(),
                AdministrativeScope.global(),
                AdministrativeGrantState.ACTIVE,
                created,
                null,
                1,
                created,
                created);
        return new AdministrativeAuthorizationService(
                (requestedTenant, identityId, permission) ->
                        allow
                                && requestedTenant.equals(tenant)
                                && identityId.equals(requesterId)
                                ? List.of(grant)
                                : List.of(),
                (requestedTenant, identityId) ->
                        requestedTenant.equals(tenant)
                                && identityId.equals(requesterId));
    }

    private GovernanceCursorCodec cursorCodec() {
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair =
                    generator.generateKeyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial(
                            "governance-api-test",
                            "SHA256withRSA",
                            pair.getPublic());
            SigningKeyProvider provider =
                    new SigningKeyProvider() {
                        @Override
                        public SigningKeyMaterial
                                currentSigningKey() {
                            return material;
                        }

                        @Override
                        public Optional<SigningKeyMaterial>
                                verificationKey(
                                        String keyId) {
                            return material.keyId()
                                            .equals(keyId)
                                    ? Optional.of(material)
                                    : Optional.empty();
                        }

                        @Override
                        public byte[] sign(
                                byte[] payload) {
                            try {
                                Signature signature =
                                        Signature.getInstance(
                                                material.signingAlgorithm());
                                signature.initSign(
                                        pair.getPrivate());
                                signature.update(payload);
                                return signature.sign();
                            } catch (Exception error) {
                                throw new IllegalStateException(
                                        error);
                            }
                        }
                    };
            return new GovernanceCursorCodec(
                    provider,
                    Duration.ofMinutes(15),
                    Clock.systemUTC());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
