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
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationRepository;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativeGrantState;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.governance.application.ReviewQueryService;
import io.wyrmgate.iam.governance.application.ReviewRepository;
import io.wyrmgate.iam.governance.application.ReviewService;
import io.wyrmgate.iam.governance.application.ReviewWorkSink;
import io.wyrmgate.iam.governance.domain.ReviewModels.ItemState;
import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewItem;
import io.wyrmgate.iam.governance.persistence.JdbcReviewRepository;
import io.wyrmgate.iam.governance.persistence.JdbcReviewWorkSink;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

class GovernanceReviewApiIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final String ACTOR_ATTRIBUTE =
            "io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext.actor";
    private static final ObjectMapper JSON =
            new ObjectMapper();

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static JdbcOutboxRepository outbox;
    private static JdbcIdempotencyRepository idempotency;
    private static TransactionExecutor transactions;

    private TenantContext tenant;
    private UUID adminId;
    private UUID reviewerId;
    private UUID outsiderId;
    private UUID scopedReaderId;
    private AuthenticatedAdministrativeActor admin;
    private AuthenticatedAdministrativeActor reviewer;
    private AuthenticatedAdministrativeActor outsider;
    private AuthenticatedAdministrativeActor scopedReader;
    private ReviewRepository repository;
    private ReviewQueryService queries;
    private ReviewService reviewService;
    private Map<UUID,AdministrativeScope> adminScopes;
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
                .isEqualTo("34");

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
                    governance.review_decision,
                    governance.review_remediation,
                    governance.review_item,
                    governance.review_campaign,
                    platform.idempotency_record,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);

        tenant = new TenantContext(
                tenants.create(
                        "Review API",
                        Instant.now()).id());
        adminId = ids.nextId();
        reviewerId = ids.nextId();
        outsiderId = ids.nextId();
        scopedReaderId = ids.nextId();
        admin = new AuthenticatedAdministrativeActor(
                tenant, adminId);
        reviewer = new AuthenticatedAdministrativeActor(
                tenant, reviewerId);
        outsider = new AuthenticatedAdministrativeActor(
                tenant, outsiderId);
        scopedReader =
                new AuthenticatedAdministrativeActor(
                        tenant, scopedReaderId);

        repository = new JdbcReviewRepository(jdbc);
        queries = new ReviewQueryService(repository);
        IdentityAccessReferenceQuery identities =
                new IdentityAccessReferenceQuery() {
                    @Override
                    public boolean identityExists(
                            TenantContext ignored,
                            UUID identityId) {
                        return true;
                    }

                    @Override
                    public PrincipalReference principal(
                            TenantContext ignored,
                            UUID principalId) {
                        return PrincipalReference.notFound();
                    }

                    @Override
                    public PrincipalSelection
                            selectUniqueActivePrincipal(
                                    TenantContext ignored,
                                    UUID identityId,
                                    UUID targetId) {
                        return PrincipalSelection.none();
                    }
                };
        ReviewWorkSink work =
                new JdbcReviewWorkSink(outbox, ids);
        reviewService = new ReviewService(
                repository,
                identities,
                work,
                ids,
                transactions);

        adminScopes = new HashMap<>();
        adminScopes.put(
                adminId,
                AdministrativeScope.global());
        AdministrativeAuthorizationRepository grants =
                (requestedTenant, actorIdentityId, permission) -> {
                    AdministrativeScope scope =
                            adminScopes.get(actorIdentityId);
                    if (scope == null) return List.of();
                    Instant now = Instant.now();
                    return List.of(new AdministrativeGrant(
                            ids.nextId(),
                            actorIdentityId,
                            ids.nextId(),
                            scope,
                            AdministrativeGrantState.ACTIVE,
                            null,
                            null,
                            1,
                            now,
                            now));
                };
        AdministrativeAuthorizationService authorization =
                new AdministrativeAuthorizationService(
                        grants,
                        (requestedTenant, identityId) -> true);

        GovernanceReviewApiMutationService mutations =
                new GovernanceReviewApiMutationService(
                        authorization,
                        reviewService,
                        repository,
                        queries,
                        idempotency,
                        transactions);
        GovernanceReviewController controller =
                new GovernanceReviewController(
                        repository,
                        queries,
                        mutations,
                        authorization,
                        ids,
                        cursorCodec());
        mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(
                        new GovernanceApiErrorHandler(ids))
                .build();
    }

    @Test
    void adminCreatesStartsAndReviewerDecidesWithoutAdminOverride()
            throws Exception {
        UUID subjectId = ids.nextId();
        String location = mvc.perform(
                        post("/api/v1/governance/review-campaigns")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        admin)
                                .header(
                                        "Idempotency-Key",
                                        "review-create-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "subjectIdentityId":"%s",
                                          "reviewerIdentityId":"%s",
                                          "snapshotAt":"2026-09-29T09:00:00Z"
                                        }
                                        """.formatted(
                                        subjectId,
                                        reviewerId)))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                "ETag", "\"rev-1\""))
                .andExpect(
                        jsonPath("$.state")
                                .value("DRAFT"))
                .andReturn()
                .getResponse()
                .getHeader("Location");
        UUID campaignId = UUID.fromString(
                location.substring(
                        location.lastIndexOf('/') + 1));

        mvc.perform(
                        post("/api/v1/governance/review-campaigns")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        admin)
                                .header(
                                        "Idempotency-Key",
                                        "review-create-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "subjectIdentityId":"%s",
                                          "reviewerIdentityId":"%s",
                                          "snapshotAt":"2026-09-29T09:00:00Z"
                                        }
                                        """.formatted(
                                        subjectId,
                                        reviewerId)))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(
                                        campaignId.toString()));

        mvc.perform(
                        post("/api/v1/governance/review-campaigns/{id}/start",
                                campaignId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        admin)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "review-start-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "ETag", "\"rev-2\""))
                .andExpect(
                        jsonPath("$.state")
                                .value("GENERATING"));

        mvc.perform(
                        post("/api/v1/governance/review-campaigns/{id}/start",
                                campaignId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        admin)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "review-start-0001"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.state")
                                .value("GENERATING"));

        mvc.perform(
                        post("/api/v1/governance/review-campaigns/{id}/start",
                                campaignId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        admin)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "review-start-stale"))
                .andExpect(
                        status().isPreconditionFailed())
                .andExpect(
                        jsonPath("$.code")
                                .value("stale_revision"));

        Instant now = Instant.now();
        ReviewItem item = new ReviewItem(
                ids.nextId(),
                campaignId,
                reviewerId,
                ids.nextId(),
                7,
                "ENTITLEMENT",
                null,
                ids.nextId(),
                "ANY",
                null,
                "MANUAL",
                null,
                "ACTIVE",
                null,
                null,
                now.minusSeconds(10),
                Instant.parse(
                        "2026-09-29T09:00:00Z"),
                ItemState.PENDING,
                1,
                now,
                now);
        assertThat(repository.insertItemIfAbsent(
                tenant, item)).isTrue();
        repository.recordGenerationPage(
                tenant,
                campaignId,
                item.assignmentCreatedAt(),
                item.accessAssignmentId(),
                1,
                true,
                2,
                now.plusSeconds(1));

        mvc.perform(
                        get("/api/v1/governance/review-inbox")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        reviewer))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items.length()")
                                .value(1))
                .andExpect(
                        jsonPath("$.items[0].id")
                                .value(
                                        item.id().toString()));

        mvc.perform(
                        post("/api/v1/governance/review-items/{id}/revoke",
                                item.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        admin)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "admin-review-decision")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reason\":\"admin cannot override\"}"))
                .andExpect(status().isForbidden());

        String response = mvc.perform(
                        post("/api/v1/governance/review-items/{id}/revoke",
                                item.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        reviewer)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "review-decision-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reason\":\"remove access\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "ETag", "\"rev-2\""))
                .andExpect(
                        jsonPath("$.state")
                                .value("DECIDED"))
                .andExpect(
                        jsonPath("$.decision.decision")
                                .value("REVOKE"))
                .andExpect(
                        jsonPath("$.remediation.state")
                                .value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode parsed = JSON.readTree(response);
        UUID remediationId = UUID.fromString(
                parsed.get("remediation")
                        .get("id")
                        .asText());

        mvc.perform(
                        post("/api/v1/governance/review-items/{id}/revoke",
                                item.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        reviewer)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "review-decision-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reason\":\"remove access\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.remediation.id")
                                .value(
                                        remediationId.toString()));

        mvc.perform(
                        post("/api/v1/governance/review-items/{id}/revoke",
                                item.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        reviewer)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "review-decision-0001")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reason\":\"different replay\"}"))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code")
                                .value("idempotency_conflict"));

        mvc.perform(
                        post("/api/v1/governance/review-items/{id}/revoke",
                                item.id())
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        reviewer)
                                .header(
                                        "If-Match",
                                        "\"rev-1\"")
                                .header(
                                        "Idempotency-Key",
                                        "review-decision-stale")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reason\":\"new stale request\"}"))
                .andExpect(
                        status().isPreconditionFailed())
                .andExpect(
                        jsonPath("$.code")
                                .value("stale_revision"));

        mvc.perform(
                        get("/api/v1/governance/review-campaigns/{id}",
                                campaignId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        reviewer))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.state")
                                .value("COMPLETED"))
                .andExpect(
                        jsonPath("$.decidedItemCount")
                                .value(1));

        mvc.perform(
                        get("/api/v1/governance/review-remediations/{id}",
                                remediationId)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        reviewer))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.state")
                                .value("PENDING"));
    }

    @Test
    void collectionAuthorizationAndReviewCursorContextsFailClosed()
            throws Exception {
        UUID first = createCampaign(
                "review-create-cursor-1",
                reviewerId);
        UUID second = createCampaign(
                "review-create-cursor-2",
                reviewerId);

        adminScopes.put(
                scopedReaderId,
                AdministrativeScope.specificResource(
                        "review-campaign",
                        first));

        mvc.perform(
                        get("/api/v1/governance/review-campaigns")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        scopedReader))
                .andExpect(status().isForbidden());

        mvc.perform(
                        get("/api/v1/governance/review-campaigns/{id}",
                                first)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        scopedReader))
                .andExpect(status().isOk());

        mvc.perform(
                        get("/api/v1/governance/review-campaigns/{id}",
                                second)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        scopedReader))
                .andExpect(status().isForbidden());

        String body = mvc.perform(
                        get("/api/v1/governance/review-campaigns")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        admin)
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.nextCursor")
                                .isString())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = JSON.readTree(body)
                .get("nextCursor")
                .asText();

        TenantContext otherTenant = new TenantContext(
                tenants.create(
                        "Other tenant",
                        Instant.now()).id());
        AuthenticatedAdministrativeActor foreignAdmin =
                new AuthenticatedAdministrativeActor(
                        otherTenant,
                        adminId);
        mvc.perform(
                        get("/api/v1/governance/review-campaigns")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        foreignAdmin)
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("validation_failed"));

        mvc.perform(
                        get("/api/v1/governance/review-campaigns")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        admin)
                                .param(
                                        "cursor",
                                        cursor.substring(
                                                0,
                                                cursor.length() - 2)
                                                + "xx"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("validation_failed"));

        mvc.perform(
                        get("/api/v1/governance/review-campaigns/{id}",
                                first)
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        outsider))
                .andExpect(status().isForbidden());
    }

    private UUID createCampaign(
            String key,
            UUID reviewerIdentityId)
            throws Exception {
        String location = mvc.perform(
                        post("/api/v1/governance/review-campaigns")
                                .requestAttr(
                                        ACTOR_ATTRIBUTE,
                                        admin)
                                .header(
                                        "Idempotency-Key",
                                        key)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "subjectIdentityId":"%s",
                                          "reviewerIdentityId":"%s",
                                          "snapshotAt":"2026-09-29T09:00:00Z"
                                        }
                                        """.formatted(
                                        ids.nextId(),
                                        reviewerIdentityId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        return UUID.fromString(
                location.substring(
                        location.lastIndexOf('/') + 1));
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
                            "governance-review-test",
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
