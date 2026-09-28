package io.wyrmgate.iam.api.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.governance.application.ApprovalPlanSpec;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.governance.application.ApprovalService;
import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.governance.persistence.JdbcApprovalOutcomeFactSink;
import io.wyrmgate.iam.governance.persistence.JdbcApprovalRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;

class GovernanceApprovalApiIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final String ACTOR_ATTRIBUTE =
            ControlPlaneActorRequestContext.class.getName() + ".actor";
    private static final Instant NOW =
            Instant.parse("2026-09-28T05:30:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcApprovalRepository repository;
    private static ApprovalService approvals;
    private static ApprovalQueryService queries;
    private static JdbcIdempotencyRepository idempotency;
    private static TransactionExecutor transactions;

    private final ObjectMapper json = new ObjectMapper();

    private TenantContext tenant;
    private AuthenticatedAdministrativeActor actor;
    private AuthenticatedAdministrativeActor other;
    private MockMvc mvc;

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
        repository = new JdbcApprovalRepository(jdbc);
        approvals = new ApprovalService(
                repository,
                new JdbcApprovalOutcomeFactSink(
                        new JdbcOutboxRepository(jdbc), ids),
                ids,
                transactions);
        queries = new ApprovalQueryService(repository);
        idempotency = new JdbcIdempotencyRepository(jdbc, ids);
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
                    governance.approval_case,
                    platform.outbox_event,
                    platform.idempotency_record,
                    platform.tenant
                CASCADE
                """);
        tenant = new TenantContext(
                tenants.create("Approval API", NOW).id());
        actor = new AuthenticatedAdministrativeActor(
                tenant, ids.nextId());
        other = new AuthenticatedAdministrativeActor(
                tenant, ids.nextId());
        mvc = mockMvc();
    }

    @Test
    void universalInboxAndDecisionApiAreParticipantBound()
            throws Exception {
        ApprovalCase requestItemCase = open(
                ApprovalCase.SubjectType.REQUEST_ITEM,
                actor.identityId());
        ApprovalCase credentialCase = open(
                ApprovalCase.SubjectType.CREDENTIAL_OPERATION,
                actor.identityId());

        String firstPage = mvc.perform(
                        get("/api/v1/approval-inbox")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
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

        JsonNode page = json.readTree(firstPage);
        String cursor = page.get("nextCursor").asText();

        mvc.perform(
                        get("/api/v1/approval-inbox")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, other)
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("validation_failed"));

        mvc.perform(
                        post("/api/v1/approval-cases/{id}/approve",
                                requestItemCase.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "approval-api-idem-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "ETag", "\"rev-2\""))
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("APPROVED"))
                .andExpect(
                        jsonPath("$.subjectType")
                                .value("REQUEST_ITEM"));

        mvc.perform(
                        post("/api/v1/approval-cases/{id}/approve",
                                requestItemCase.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "approval-api-idem-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("APPROVED"));

        mvc.perform(
                        post("/api/v1/approval-cases/{id}/reject",
                                credentialCase.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, other)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "approval-api-other-0001"))
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "approval_actor_not_participant"));

        mvc.perform(
                        post("/api/v1/approval-cases/{id}/reject",
                                credentialCase.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "approval-api-idem-0002"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.lifecycleState")
                                .value("REJECTED"))
                .andExpect(
                        jsonPath("$.subjectType")
                                .value("CREDENTIAL_OPERATION"));

        mvc.perform(
                        get("/api/v1/approval-inbox")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE, actor))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items.length()")
                                .value(0));
    }

    private ApprovalCase open(
            ApprovalCase.SubjectType type,
            UUID approver) {
        return approvals.openCase(
                tenant,
                type,
                ids.nextId(),
                1,
                ids.nextId(),
                new ApprovalPlanSpec(
                        ApprovalPlanSpec.SelfApprovalPolicy.DENY_REQUESTER,
                        List.of(new ApprovalPlanSpec.StageSpec(
                                ApprovalPlanSpec.DecisionMode.ANY_ONE,
                                List.of(approver)))),
                NOW,
                ids.nextId(),
                null);
    }

    private MockMvc mockMvc() {
        GovernanceApprovalApiMutationService mutations =
                new GovernanceApprovalApiMutationService(
                        approvals,
                        queries,
                        idempotency,
                        transactions);
        GovernanceApprovalController controller =
                new GovernanceApprovalController(
                        queries,
                        mutations,
                        ids,
                        testCursorCodec());
        return MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(
                        new GovernanceApprovalApiErrorHandler(ids))
                .build();
    }

    private GovernanceApprovalCursorCodec testCursorCodec() {
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            SigningKeyMaterial material =
                    new SigningKeyMaterial(
                            "approval-api-test",
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
                                verificationKey(String keyId) {
                            return material.keyId().equals(keyId)
                                    ? Optional.of(material)
                                    : Optional.empty();
                        }

                        @Override
                        public byte[] sign(byte[] payload) {
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
            return new GovernanceApprovalCursorCodec(
                    provider,
                    Duration.ofMinutes(15),
                    Clock.systemUTC());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
