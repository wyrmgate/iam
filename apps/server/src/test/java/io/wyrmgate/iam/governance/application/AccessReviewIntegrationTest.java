package io.wyrmgate.iam.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.wyrmgate.iam.access.application.*;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.persistence.JdbcAccessAssignmentRepository;
import io.wyrmgate.iam.access.persistence.JdbcAccessReviewRemediationRepository;
import io.wyrmgate.iam.governance.domain.ReviewModels.*;
import io.wyrmgate.iam.governance.persistence.JdbcReviewRepository;
import io.wyrmgate.iam.governance.persistence.JdbcReviewWorkSink;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.persistence.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

class AccessReviewIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW =
            Instant.parse("2026-09-29T09:00:00Z");

    private static JdbcTemplate jdbc;
    private static IdGenerator ids;
    private static JdbcTenantRepository tenants;
    private static TransactionExecutor transactions;

    private TenantContext tenant;
    private JdbcOutboxRepository outbox;
    private JdbcReviewRepository reviews;
    private ReviewService reviewService;
    private ReviewGenerationProcessingService generation;
    private ReviewRemediationProcessingService remediation;
    private JdbcAccessAssignmentRepository assignments;
    private AccessAssignmentCommandService assignmentCommands;
    private AccessReviewRemediationCommand accessRemediation;

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
                .isEqualTo("35");

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
                    access.review_remediation_application,
                    governance.review_remediation,
                    governance.review_decision,
                    governance.review_item,
                    governance.review_campaign,
                    access.effective_access_support,
                    access.effective_access,
                    access.access_assignment,
                    access.desired_grant_state,
                    access.desired_principal_state,
                    platform.outbox_event,
                    platform.tenant
                CASCADE
                """);

        tenant = new TenantContext(
                tenants.create(
                        "Access review test", NOW).id());
        outbox = new JdbcOutboxRepository(jdbc);
        reviews = new JdbcReviewRepository(jdbc);
        ReviewWorkSink work =
                new JdbcReviewWorkSink(
                        outbox, ids);
        reviewService = new ReviewService(
                reviews,
                identities,
                work,
                ids,
                transactions);
        assignments =
                new JdbcAccessAssignmentRepository(jdbc);
        assignmentCommands =
                new AccessAssignmentCommandService(
                        assignments,
                        identities,
                        (requestedTenant, entitlementId) ->
                                io.wyrmgate.iam.catalog.application
                                        .CatalogAccessReferenceQuery
                                        .EntitlementReference.notFound(),
                        (requestedTenant, assignment) -> { },
                        (requestedTenant, assignment, now) -> { },
                        ids,
                        transactions);
        AccessReviewSnapshotQuery snapshots =
                new AccessReviewSnapshotQueryService(
                        assignments);
        var applicationRepository =
                new JdbcAccessReviewRemediationRepository(
                        jdbc);
        accessRemediation =
                new AccessReviewRemediationCommandService(
                        assignments,
                        assignmentCommands,
                        applicationRepository,
                        transactions);
        generation =
                new ReviewGenerationProcessingService(
                        outbox,
                        reviews,
                        snapshots,
                        work,
                        ids,
                        transactions,
                        Clock.fixed(
                                NOW.plusSeconds(10),
                                ZoneOffset.UTC));
        remediation =
                new ReviewRemediationProcessingService(
                        outbox,
                        reviews,
                        accessRemediation,
                        transactions,
                        Clock.fixed(
                                NOW.plusSeconds(100),
                                ZoneOffset.UTC));
    }

    @Test
    void generationPagesResumesAndExcludesTerminalOrExpiredAssignments() {
        UUID subject = ids.nextId();
        UUID reviewer = ids.nextId();

        for (int i = 0; i < 205; i++) {
            insertAssignment(
                    subject,
                    AccessAssignment.LifecycleState.ACTIVE,
                    null,
                    NOW.plusSeconds(3600),
                    NOW.minusSeconds(500 - i));
        }
        insertAssignment(
                subject,
                AccessAssignment.LifecycleState.REVOKED,
                null,
                null,
                NOW.minusSeconds(1000));
        insertAssignment(
                subject,
                AccessAssignment.LifecycleState.ACTIVE,
                null,
                NOW.minusSeconds(1),
                NOW.minusSeconds(999));

        ReviewCampaign draft =
                reviewService.createIdentityAccessCampaign(
                        tenant,
                        subject,
                        reviewer,
                        NOW,
                        NOW.plusSeconds(1));
        ReviewCampaign generating =
                reviewService.startGeneration(
                        tenant,
                        draft.id(),
                        draft.revision(),
                        NOW.plusSeconds(2));
        assertThat(generating.state())
                .isEqualTo(CampaignState.GENERATING);

        assertThat(generation.processAvailable().processed())
                .isEqualTo(1);
        ReviewCampaign afterFirst =
                reviews.findCampaign(
                                tenant, draft.id())
                        .orElseThrow();
        assertThat(afterFirst.state())
                .isEqualTo(CampaignState.GENERATING);
        assertThat(afterFirst.generatedItemCount())
                .isEqualTo(200);
        assertThat(afterFirst.generationAfterId())
                .isNotNull();

        assertThat(generation.processAvailable().processed())
                .isEqualTo(1);
        ReviewCampaign active =
                reviews.findCampaign(
                                tenant, draft.id())
                        .orElseThrow();
        assertThat(active.state())
                .isEqualTo(CampaignState.ACTIVE);
        assertThat(active.generatedItemCount())
                .isEqualTo(205);
        assertThat(active.decidedItemCount())
                .isZero();

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM governance.review_item
                WHERE tenant_id = ?
                  AND review_campaign_id = ?
                """,
                Long.class,
                tenant.tenantId(),
                draft.id()))
                .isEqualTo(205L);

        assertThat(generation.processAvailable().claimed())
                .isZero();
    }

    @Test
    void decisionsAreImmutableCampaignCompletesBeforeRemediationAndReplayIsStable() {
        UUID subject = ids.nextId();
        UUID reviewer = ids.nextId();
        UUID outsider = ids.nextId();

        AccessAssignment kept =
                insertAssignment(
                        subject,
                        AccessAssignment.LifecycleState.ACTIVE,
                        null,
                        null,
                        NOW.minusSeconds(100));
        AccessAssignment revoked =
                insertAssignment(
                        subject,
                        AccessAssignment.LifecycleState.SUSPENDED,
                        null,
                        null,
                        NOW.minusSeconds(90));

        ReviewCampaign campaign =
                startAndGenerate(
                        subject, reviewer);

        var itemRows = jdbc.queryForList("""
                SELECT id, access_assignment_id
                FROM governance.review_item
                WHERE tenant_id = ?
                  AND review_campaign_id = ?
                ORDER BY assignment_created_at, id
                """,
                tenant.tenantId(),
                campaign.id());
        UUID keepItem = itemRows.stream()
                .filter(row -> kept.id().equals(
                        row.get("access_assignment_id")))
                .map(row -> (UUID) row.get("id"))
                .findFirst()
                .orElseThrow();
        UUID revokeItem = itemRows.stream()
                .filter(row -> revoked.id().equals(
                        row.get("access_assignment_id")))
                .map(row -> (UUID) row.get("id"))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() ->
                reviewService.decide(
                        tenant,
                        keepItem,
                        outsider,
                        DecisionValue.KEEP,
                        null,
                        1,
                        NOW.plusSeconds(20)))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "assigned reviewer");

        var keepResult = reviewService.decide(
                tenant,
                keepItem,
                reviewer,
                DecisionValue.KEEP,
                "still required",
                1,
                NOW.plusSeconds(21));
        assertThat(keepResult.remediation())
                .isNull();
        assertThat(keepResult.campaign().state())
                .isEqualTo(CampaignState.ACTIVE);

        var revokeResult = reviewService.decide(
                tenant,
                revokeItem,
                reviewer,
                DecisionValue.REVOKE,
                "remove access",
                1,
                NOW.plusSeconds(22));
        assertThat(revokeResult.campaign().state())
                .isEqualTo(CampaignState.COMPLETED);
        assertThat(revokeResult.remediation())
                .isNotNull();
        assertThat(revokeResult.remediation().state())
                .isEqualTo(RemediationState.PENDING);

        assertThat(assignments.findById(
                tenant, revoked.id()).orElseThrow()
                .lifecycleState())
                .isEqualTo(
                        AccessAssignment.LifecycleState.SUSPENDED);

        UUID decisionId = jdbc.queryForObject("""
                SELECT id
                FROM governance.review_decision
                WHERE tenant_id = ?
                  AND review_item_id = ?
                """,
                UUID.class,
                tenant.tenantId(),
                revokeItem);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE governance.review_decision
                SET reason = 'rewritten'
                WHERE tenant_id = ? AND id = ?
                """,
                tenant.tenantId(),
                decisionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(
                        "immutable evidence");

        assertThat(remediation.processAvailable().processed())
                .isEqualTo(1);
        ReviewRemediation completed =
                reviews.findRemediation(
                                tenant,
                                revokeResult.remediation().id())
                        .orElseThrow();
        assertThat(completed.state())
                .isEqualTo(RemediationState.APPLIED);
        assertThat(assignments.findById(
                tenant, revoked.id()).orElseThrow()
                .lifecycleState())
                .isEqualTo(
                        AccessAssignment.LifecycleState.REVOKED);

        AccessReviewRemediationCommand.Result replay =
                accessRemediation.apply(
                        tenant,
                        completed.id(),
                        revoked.id(),
                        NOW.plusSeconds(30));
        assertThat(replay.outcome())
                .isEqualTo(
                        AccessReviewRemediationCommand.Outcome.APPLIED);
        assertThat(replay.resultingLifecycleState())
                .isEqualTo("REVOKED");

        assertThat(assignments.findById(
                tenant, kept.id()).orElseThrow()
                .lifecycleState())
                .isEqualTo(
                        AccessAssignment.LifecycleState.ACTIVE);
    }

    @Test
    void remediationUsesCurrentStateAndReturnsNoActionWhenAccessWasAlreadyRemoved() {
        UUID subject = ids.nextId();
        UUID reviewer = ids.nextId();
        AccessAssignment assignment =
                insertAssignment(
                        subject,
                        AccessAssignment.LifecycleState.ACTIVE,
                        null,
                        null,
                        NOW.minusSeconds(100));

        ReviewCampaign campaign =
                startAndGenerate(
                        subject, reviewer);
        UUID itemId = jdbc.queryForObject("""
                SELECT id
                FROM governance.review_item
                WHERE tenant_id = ?
                  AND review_campaign_id = ?
                """,
                UUID.class,
                tenant.tenantId(),
                campaign.id());

        var decision = reviewService.decide(
                tenant,
                itemId,
                reviewer,
                DecisionValue.REVOKE,
                null,
                1,
                NOW.plusSeconds(20));

        assignmentCommands.terminate(
                tenant,
                assignment.id(),
                assignment.revision(),
                NOW.plusSeconds(21));

        assertThat(remediation.processAvailable().processed())
                .isEqualTo(1);
        ReviewRemediation completed =
                reviews.findRemediation(
                                tenant,
                                decision.remediation().id())
                        .orElseThrow();
        assertThat(completed.state())
                .isEqualTo(
                        RemediationState.NO_ACTION_REQUIRED);
        assertThat(completed.resultingAccessState())
                .isEqualTo("REVOKED");

        assertThat(reviews.findCampaign(
                tenant, campaign.id()).orElseThrow().state())
                .isEqualTo(CampaignState.COMPLETED);
    }

    @Test
    void rejectsSelfReviewAndZeroItemCampaignCompletesGeneration() {
        UUID subject = ids.nextId();

        assertThatThrownBy(() ->
                reviewService.createIdentityAccessCampaign(
                        tenant,
                        subject,
                        subject,
                        NOW,
                        NOW))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "differ from subject");

        ReviewCampaign draft =
                reviewService.createIdentityAccessCampaign(
                        tenant,
                        subject,
                        ids.nextId(),
                        NOW,
                        NOW.plusSeconds(1));
        reviewService.startGeneration(
                tenant,
                draft.id(),
                draft.revision(),
                NOW.plusSeconds(2));
        generation.processAvailable();

        ReviewCampaign completed =
                reviews.findCampaign(
                                tenant, draft.id())
                        .orElseThrow();
        assertThat(completed.state())
                .isEqualTo(CampaignState.COMPLETED);
        assertThat(completed.generatedItemCount())
                .isZero();
        assertThat(completed.decidedItemCount())
                .isZero();
    }

    private ReviewCampaign startAndGenerate(
            UUID subject,
            UUID reviewer) {
        ReviewCampaign draft =
                reviewService.createIdentityAccessCampaign(
                        tenant,
                        subject,
                        reviewer,
                        NOW,
                        NOW.plusSeconds(1));
        reviewService.startGeneration(
                tenant,
                draft.id(),
                draft.revision(),
                NOW.plusSeconds(2));
        assertThat(generation.processAvailable().processed())
                .isEqualTo(1);
        return reviews.findCampaign(
                        tenant, draft.id())
                .orElseThrow();
    }

    private AccessAssignment insertAssignment(
            UUID identityId,
            AccessAssignment.LifecycleState state,
            Instant validFrom,
            Instant validUntil,
            Instant createdAt) {
        AccessAssignment assignment =
                new AccessAssignment(
                        ids.nextId(),
                        identityId,
                        AccessAssignment.TargetKind.ENTITLEMENT,
                        null,
                        ids.nextId(),
                        AccessAssignment.PrincipalConstraintKind.ANY,
                        null,
                        AccessAssignment.ProvenanceKind.MANUAL,
                        null,
                        state,
                        validFrom,
                        validUntil,
                        1,
                        createdAt,
                        createdAt);
        assignments.insert(tenant, assignment);
        return assignment;
    }
}
