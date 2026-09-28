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
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.governance.application.AccessRequestApprovalResultSink;
import io.wyrmgate.iam.governance.application.AccessRequestCommandService;
import io.wyrmgate.iam.governance.application.AccessRequestEvaluationProcessingService;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityResult;
import io.wyrmgate.iam.governance.application.ApprovalCommandService;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionMode;
import io.wyrmgate.iam.governance.application.ApprovalModels.PlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalModels.StageSpec;
import io.wyrmgate.iam.governance.application.ApprovalReadService;
import io.wyrmgate.iam.governance.persistence.JdbcAccessRequestRepository;
import io.wyrmgate.iam.governance.persistence.JdbcApprovalRepository;
import io.wyrmgate.iam.governance.persistence.JdbcSubmittedRequestItemSink;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQueryService;
import io.wyrmgate.iam.identity.application.IdentityCommandService;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityFactSink;
import io.wyrmgate.iam.identity.persistence.JdbcIdentityRepository;
import io.wyrmgate.iam.identity.persistence.JdbcPrincipalRepository;
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
            ControlPlaneActorRequestContext.class.getName()
                    + ".actor";
    private static final Instant NOW =
            Instant.parse("2026-09-28T11:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdempotencyRepository idempotency;
    private static TransactionExecutor transactions;
    private static IdentityCommandService identities;
    private static JdbcIdentityRepository identityRepository;
    private static IdentityAccessReferenceQueryService identityReferences;

    private final ObjectMapper json =
            new ObjectMapper().findAndRegisterModules();

    private TenantContext tenant;
    private Identity requester;
    private Identity firstApprover;
    private Identity secondApprover;
    private Identity outsider;
    private JdbcAccessRequestRepository requestRepository;
    private JdbcApprovalRepository approvalRepository;
    private AccessRequestCommandService requestCommands;
    private ApprovalCommandService approvalCommands;
    private ApprovalReadService approvalReads;
    private GovernanceApiMutationService mutations;
    private GovernanceCursorCodec cursors;

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
                .isEqualTo("27");

        jdbc = new JdbcTemplate(dataSource);
        ids = new UuidV7Generator();
        tenants = new JdbcTenantRepository(jdbc, ids);
        outbox = new JdbcOutboxRepository(jdbc);
        idempotency = new JdbcIdempotencyRepository(
                jdbc, ids);
        transactions = new SpringTransactionExecutor(
                new DataSourceTransactionManager(dataSource));
        identityRepository =
                new JdbcIdentityRepository(jdbc);
        identities = new IdentityCommandService(
                identityRepository,
                new JdbcIdentityFactSink(outbox, ids),
                ids,
                transactions);
        identityReferences =
                new IdentityAccessReferenceQueryService(
                        identityRepository,
                        new JdbcPrincipalRepository(jdbc));
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
                    platform.idempotency_record,
                    platform.outbox_event,
                    identity.principal,
                    identity.person_profile,
                    identity.service_profile,
                    identity.workload_profile,
                    identity.identity,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create(
                        "Governance API", NOW).id());
        requester = identity("Requester");
        firstApprover = identity("First Approver");
        secondApprover = identity("Second Approver");
        outsider = identity("Outsider");

        requestRepository =
                new JdbcAccessRequestRepository(jdbc);
        approvalRepository =
                new JdbcApprovalRepository(jdbc);
        approvalCommands =
                new ApprovalCommandService(
                        approvalRepository,
                        new AccessRequestApprovalResultSink(
                                requestRepository,
                                (requestedTenant, item) -> { }),
                        ids,
                        transactions);
        requestCommands =
                new AccessRequestCommandService(
                        requestRepository,
                        (requestedTenant, request, item) ->
                                EligibilityResult
                                        .approvalRequired(
                                                new PlanSpec(
                                                        List.of(
                                                                new StageSpec(
                                                                        DecisionMode.ALL,
                                                                        List.of(
                                                                                firstApprover.id(),
                                                                                secondApprover.id()))))),
                        approvalCommands,
                        new JdbcSubmittedRequestItemSink(
                                outbox, ids),
                        (requestedTenant, item) -> { },
                        ids,
                        transactions);
        approvalReads =
                new ApprovalReadService(
                        approvalRepository);
        AdministrativeAuthorizationService authorization =
                new AdministrativeAuthorizationService(
                        (requestedTenant,
                                identityId,
                                permission) ->
                                List.of(),
                        (requestedTenant, identityId) ->
                                requestedTenant.equals(tenant));
        mutations =
                new GovernanceApiMutationService(
                        authorization,
                        identityReferences,
                        requestCommands,
                        requestRepository,
                        approvalCommands,
                        approvalRepository,
                        idempotency,
                        transactions);
        cursors = testCursorCodec();
    }

    @Test
    void selfServiceRequestFlowsThroughDurableEvaluationAndReusableApprovalApi()
            throws Exception {
        MockMvc requesterApi = mockMvc(requester.id());
        String createBody = """
                {
                  "beneficiaryIdentityId":"%s",
                  "items":[{
                    "targetKind":"ENTITLEMENT",
                    "targetId":"%s",
                    "principalConstraintKind":"ANY",
                    "specificPrincipalId":null,
                    "validFrom":null,
                    "validUntil":null
                  }]
                }
                """.formatted(
                requester.id(),
                ids.nextId());

        String createdJson = requesterApi.perform(
                        post("/api/v1/access-requests")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(requester.id()))
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
                                        requester.id().toString()))
                .andExpect(
                        jsonPath("$.state")
                                .value("DRAFT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode created = json.readTree(createdJson);
        UUID requestId = UUID.fromString(
                created.get("id").asText());
        UUID itemId = UUID.fromString(
                created.get("items")
                        .get(0)
                        .get("id")
                        .asText());

        requesterApi.perform(
                        post("/api/v1/access-requests")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(requester.id()))
                                .header(
                                        "Idempotency-Key",
                                        "request-create-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(requestId.toString()));

        requesterApi.perform(
                        post("/api/v1/access-requests/{id}/submit",
                                requestId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(requester.id()))
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

        requesterApi.perform(
                        post("/api/v1/access-requests/{id}/submit",
                                requestId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(requester.id()))
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "request-submit-stale-1"))
                .andExpect(
                        status().isPreconditionFailed())
                .andExpect(
                        jsonPath("$.code")
                                .value("stale_revision"));

        var evaluator = new AccessRequestEvaluationProcessingService(
                outbox,
                requestRepository,
                requestCommands);
        assertThat(evaluator.processAvailable().processed())
                .isEqualTo(1);

        String itemJson = requesterApi.perform(
                        get("/api/v1/request-items/{id}",
                                itemId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(requester.id())))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.state")
                                .value("PENDING_APPROVAL"))
                .andExpect(
                        jsonPath("$.approvalCaseId")
                                .isString())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID caseId = UUID.fromString(
                json.readTree(itemJson)
                        .get("approvalCaseId")
                        .asText());

        requesterApi.perform(
                        get("/api/v1/approval-cases/{id}",
                                caseId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(requester.id())))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.stages[0].approvers.length()")
                                .value(2));

        mockMvc(outsider.id()).perform(
                        get("/api/v1/approval-cases/{id}",
                                caseId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(outsider.id())))
                .andExpect(status().isNotFound());

        mockMvc(firstApprover.id()).perform(
                        get("/api/v1/approval-inbox")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(firstApprover.id())))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[0].id")
                                .value(caseId.toString()));

        mockMvc(firstApprover.id()).perform(
                        post("/api/v1/approval-cases/{id}/approve",
                                caseId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(firstApprover.id()))
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "approval-first-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reason\":\"looks good\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "ETag", "\"rev-2\""))
                .andExpect(
                        jsonPath("$.state")
                                .value("PENDING"))
                .andExpect(
                        jsonPath("$.stages[0].decisions.length()")
                                .value(1));

        mockMvc(secondApprover.id()).perform(
                        post("/api/v1/approval-cases/{id}/approve",
                                caseId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(secondApprover.id()))
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "approval-second-stale"))
                .andExpect(
                        status().isPreconditionFailed())
                .andExpect(
                        jsonPath("$.code")
                                .value("stale_revision"));

        MockMvc secondApi =
                mockMvc(secondApprover.id());
        secondApi.perform(
                        post("/api/v1/approval-cases/{id}/approve",
                                caseId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(secondApprover.id()))
                                .header(
                                        "If-Match",
                                        "\"rev-2\"")
                                .header(
                                        "Idempotency-Key",
                                        "approval-second-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "ETag", "\"rev-3\""))
                .andExpect(
                        jsonPath("$.state")
                                .value("APPROVED"));

        secondApi.perform(
                        post("/api/v1/approval-cases/{id}/approve",
                                caseId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(secondApprover.id()))
                                .header(
                                        "If-Match",
                                        "\"rev-2\"")
                                .header(
                                        "Idempotency-Key",
                                        "approval-second-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.state")
                                .value("APPROVED"));

        requesterApi.perform(
                        get("/api/v1/request-items/{id}",
                                itemId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(requester.id())))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.state")
                                .value("AUTHORIZED"));
    }

    @Test
    void requestForAnotherIdentityFailsClosedWithoutAdministrativePermission()
            throws Exception {
        mockMvc(requester.id()).perform(
                        post("/api/v1/access-requests")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        actor(requester.id()))
                                .header(
                                        "Idempotency-Key",
                                        "request-other-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "beneficiaryIdentityId":"%s",
                                          "items":[{
                                            "targetKind":"ENTITLEMENT",
                                            "targetId":"%s",
                                            "principalConstraintKind":"ANY"
                                          }]
                                        }
                                        """.formatted(
                                        outsider.id(),
                                        ids.nextId())))
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.code")
                                .value("forbidden"));
    }

    private MockMvc mockMvc(UUID identityId) {
        AdministrativeAuthorizationService authorization =
                new AdministrativeAuthorizationService(
                        (requestedTenant,
                                requestedIdentityId,
                                permission) ->
                                List.of(),
                        (requestedTenant,
                                requestedIdentityId) ->
                                requestedTenant.equals(tenant));
        GovernanceController controller =
                new GovernanceController(
                        requestRepository,
                        approvalReads,
                        mutations,
                        authorization,
                        ids,
                        cursors);
        return MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(
                        new GovernanceApiErrorHandler(ids))
                .build();
    }

    private AuthenticatedAdministrativeActor actor(
            UUID identityId) {
        return new AuthenticatedAdministrativeActor(
                tenant, identityId);
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

    private GovernanceCursorCodec testCursorCodec() {
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
