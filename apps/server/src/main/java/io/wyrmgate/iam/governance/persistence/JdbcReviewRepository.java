package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.ReviewRepository;
import io.wyrmgate.iam.governance.domain.ReviewModels.*;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcReviewRepository
        implements ReviewRepository {

    private final JdbcTemplate jdbc;

    public JdbcReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertCampaign(
            TenantContext tenant,
            ReviewCampaign campaign) {
        jdbc.update("""
                INSERT INTO governance.review_campaign (
                    id, tenant_id, campaign_kind,
                    subject_identity_id, reviewer_identity_id,
                    snapshot_at, lifecycle_state,
                    generation_after_created_at,
                    generation_after_id,
                    generated_item_count, decided_item_count,
                    revision, created_at, updated_at,
                    activated_at, completed_at, failure_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                campaign.id(),
                tenant.tenantId(),
                campaign.kind().name(),
                campaign.subjectIdentityId(),
                campaign.reviewerIdentityId(),
                Timestamp.from(campaign.snapshotAt()),
                campaign.state().name(),
                timestamp(campaign.generationAfterCreatedAt()),
                campaign.generationAfterId(),
                campaign.generatedItemCount(),
                campaign.decidedItemCount(),
                campaign.revision(),
                Timestamp.from(campaign.createdAt()),
                Timestamp.from(campaign.updatedAt()),
                timestamp(campaign.activatedAt()),
                timestamp(campaign.completedAt()),
                campaign.failureCode());
    }

    @Override
    public Optional<ReviewCampaign> findCampaign(
            TenantContext tenant,
            UUID campaignId) {
        return jdbc.query("""
                SELECT id, campaign_kind, subject_identity_id,
                       reviewer_identity_id, snapshot_at,
                       lifecycle_state, generation_after_created_at,
                       generation_after_id, generated_item_count,
                       decided_item_count, revision, created_at,
                       updated_at, activated_at, completed_at,
                       failure_code
                FROM governance.review_campaign
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> campaign(rs),
                tenant.tenantId(),
                campaignId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ReviewCampaign> findCampaignPage(
            TenantContext tenant,
            Instant afterCreatedAt,
            UUID afterId,
            int limit) {
        if (limit < 1 || limit > 201) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 201");
        }
        if ((afterCreatedAt == null) != (afterId == null)) {
            throw new IllegalArgumentException(
                    "campaign continuation must be complete or absent");
        }
        return jdbc.query("""
                SELECT id, campaign_kind, subject_identity_id,
                       reviewer_identity_id, snapshot_at,
                       lifecycle_state, generation_after_created_at,
                       generation_after_id, generated_item_count,
                       decided_item_count, revision, created_at,
                       updated_at, activated_at, completed_at,
                       failure_code
                FROM governance.review_campaign
                WHERE tenant_id = ?
                  AND (
                        ?::timestamptz IS NULL
                        OR created_at > ?
                        OR (created_at = ? AND id > ?)
                  )
                ORDER BY created_at, id
                LIMIT ?
                """,
                (rs,row) -> campaign(rs),
                tenant.tenantId(),
                timestamp(afterCreatedAt),
                timestamp(afterCreatedAt),
                timestamp(afterCreatedAt),
                afterId,
                limit);
    }

    @Override
    public ReviewCampaign startGeneration(
            TenantContext tenant,
            UUID campaignId,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE governance.review_campaign
                SET lifecycle_state = 'GENERATING',
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND lifecycle_state = 'DRAFT'
                  AND revision = ?
                """,
                Timestamp.from(now),
                tenant.tenantId(),
                campaignId,
                expectedRevision);
        if (affected != 1) {
            requireRevision(tenant, campaignId, expectedRevision);
            throw new IllegalStateException(
                    "only DRAFT ReviewCampaign can start generation");
        }
        return findCampaign(tenant, campaignId).orElseThrow();
    }

    @Override
    public ReviewCampaign recordGenerationPage(
            TenantContext tenant,
            UUID campaignId,
            Instant nextCreatedAt,
            UUID nextId,
            int insertedCount,
            boolean complete,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE governance.review_campaign
                SET generation_after_created_at = ?,
                    generation_after_id = ?,
                    generated_item_count =
                        generated_item_count + ?,
                    lifecycle_state =
                        CASE
                            WHEN ?
                                 AND generated_item_count + ? = 0
                                THEN 'COMPLETED'
                            WHEN ? THEN 'ACTIVE'
                            ELSE 'GENERATING'
                        END,
                    activated_at =
                        CASE
                            WHEN ? AND generated_item_count + ? > 0
                                THEN ?
                            ELSE activated_at
                        END,
                    completed_at =
                        CASE
                            WHEN ?
                                 AND generated_item_count + ? = 0
                                THEN ?
                            ELSE completed_at
                        END,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND lifecycle_state = 'GENERATING'
                  AND revision = ?
                """,
                timestamp(nextCreatedAt),
                nextId,
                insertedCount,
                complete,
                insertedCount,
                complete,
                complete,
                insertedCount,
                Timestamp.from(now),
                complete,
                insertedCount,
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                campaignId,
                expectedRevision);
        if (affected != 1) {
            requireRevision(tenant, campaignId, expectedRevision);
            throw new IllegalStateException(
                    "ReviewCampaign is not generating");
        }
        return findCampaign(tenant, campaignId).orElseThrow();
    }

    @Override
    public boolean insertItemIfAbsent(
            TenantContext tenant,
            ReviewItem item) {
        int affected = jdbc.update("""
                INSERT INTO governance.review_item (
                    id, tenant_id, review_campaign_id,
                    reviewer_identity_id, access_assignment_id,
                    assignment_revision, target_kind, role_id,
                    entitlement_id, principal_constraint_kind,
                    specific_principal_id, provenance_kind,
                    provenance_ref_id, snapshot_lifecycle_state,
                    valid_from, valid_until,
                    assignment_created_at, snapshot_at,
                    lifecycle_state, revision,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    tenant_id, review_campaign_id,
                    access_assignment_id)
                DO NOTHING
                """,
                item.id(),
                tenant.tenantId(),
                item.reviewCampaignId(),
                item.reviewerIdentityId(),
                item.accessAssignmentId(),
                item.assignmentRevision(),
                item.targetKind(),
                item.roleId(),
                item.entitlementId(),
                item.principalConstraintKind(),
                item.specificPrincipalId(),
                item.provenanceKind(),
                item.provenanceRefId(),
                item.snapshotLifecycleState(),
                timestamp(item.validFrom()),
                timestamp(item.validUntil()),
                Timestamp.from(item.assignmentCreatedAt()),
                Timestamp.from(item.snapshotAt()),
                item.state().name(),
                item.revision(),
                Timestamp.from(item.createdAt()),
                Timestamp.from(item.updatedAt()));
        return affected == 1;
    }

    @Override
    public Optional<ReviewItem> findItem(
            TenantContext tenant,
            UUID reviewItemId) {
        return jdbc.query("""
                SELECT id, review_campaign_id,
                       reviewer_identity_id, access_assignment_id,
                       assignment_revision, target_kind,
                       role_id, entitlement_id,
                       principal_constraint_kind,
                       specific_principal_id, provenance_kind,
                       provenance_ref_id,
                       snapshot_lifecycle_state,
                       valid_from, valid_until,
                       assignment_created_at, snapshot_at,
                       lifecycle_state, revision,
                       created_at, updated_at
                FROM governance.review_item
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> item(rs),
                tenant.tenantId(),
                reviewItemId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ReviewItem> findCampaignItemPage(
            TenantContext tenant,
            UUID campaignId,
            Instant afterCreatedAt,
            UUID afterId,
            int limit) {
        return findItemPage(
                tenant,
                "review_campaign_id = ?",
                campaignId,
                afterCreatedAt,
                afterId,
                limit);
    }

    @Override
    public List<ReviewItem> findReviewerPendingPage(
            TenantContext tenant,
            UUID reviewerIdentityId,
            Instant afterCreatedAt,
            UUID afterId,
            int limit) {
        return findItemPage(
                tenant,
                "reviewer_identity_id = ? AND lifecycle_state = 'PENDING'",
                reviewerIdentityId,
                afterCreatedAt,
                afterId,
                limit);
    }

    private List<ReviewItem> findItemPage(
            TenantContext tenant,
            String predicate,
            UUID subjectId,
            Instant afterCreatedAt,
            UUID afterId,
            int limit) {
        if (limit < 1 || limit > 201) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 201");
        }
        if ((afterCreatedAt == null) != (afterId == null)) {
            throw new IllegalArgumentException(
                    "review item continuation must be complete or absent");
        }
        String sql = """
                SELECT id, review_campaign_id,
                       reviewer_identity_id, access_assignment_id,
                       assignment_revision, target_kind,
                       role_id, entitlement_id,
                       principal_constraint_kind,
                       specific_principal_id, provenance_kind,
                       provenance_ref_id,
                       snapshot_lifecycle_state,
                       valid_from, valid_until,
                       assignment_created_at, snapshot_at,
                       lifecycle_state, revision,
                       created_at, updated_at
                FROM governance.review_item
                WHERE tenant_id = ?
                  AND %s
                  AND (
                        ?::timestamptz IS NULL
                        OR created_at > ?
                        OR (created_at = ? AND id > ?)
                  )
                ORDER BY created_at, id
                LIMIT ?
                """.formatted(predicate);
        return jdbc.query(
                sql,
                (rs,row) -> item(rs),
                tenant.tenantId(),
                subjectId,
                timestamp(afterCreatedAt),
                timestamp(afterCreatedAt),
                timestamp(afterCreatedAt),
                afterId,
                limit);
    }

    @Override
    public Optional<ReviewDecision> findDecisionByItem(
            TenantContext tenant,
            UUID reviewItemId) {
        return jdbc.query("""
                SELECT id, review_item_id,
                       reviewer_identity_id, decision,
                       reason, decided_at
                FROM governance.review_decision
                WHERE tenant_id = ?
                  AND review_item_id = ?
                """,
                (rs,row) -> new ReviewDecision(
                        rs.getObject("id", UUID.class),
                        rs.getObject(
                                "review_item_id", UUID.class),
                        rs.getObject(
                                "reviewer_identity_id", UUID.class),
                        DecisionValue.valueOf(
                                rs.getString("decision")),
                        rs.getString("reason"),
                        rs.getTimestamp(
                                "decided_at").toInstant()),
                tenant.tenantId(),
                reviewItemId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ReviewRemediation> findRemediationByItem(
            TenantContext tenant,
            UUID reviewItemId) {
        return jdbc.query("""
                SELECT id, review_item_id,
                       access_assignment_id,
                       lifecycle_state, result_code,
                       resulting_access_state, revision,
                       created_at, updated_at, completed_at
                FROM governance.review_remediation
                WHERE tenant_id = ?
                  AND review_item_id = ?
                """,
                (rs,row) -> remediation(rs),
                tenant.tenantId(),
                reviewItemId)
                .stream()
                .findFirst();
    }

    @Override
    public void insertDecision(
            TenantContext tenant,
            ReviewDecision decision) {
        jdbc.update("""
                INSERT INTO governance.review_decision (
                    id, tenant_id, review_item_id,
                    reviewer_identity_id, decision,
                    reason, decided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                decision.id(),
                tenant.tenantId(),
                decision.reviewItemId(),
                decision.reviewerIdentityId(),
                decision.decision().name(),
                decision.reason(),
                Timestamp.from(decision.decidedAt()));
    }

    @Override
    public ReviewItem markItemDecided(
            TenantContext tenant,
            UUID reviewItemId,
            long expectedRevision,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE governance.review_item
                SET lifecycle_state = 'DECIDED',
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND lifecycle_state = 'PENDING'
                  AND revision = ?
                """,
                Timestamp.from(now),
                tenant.tenantId(),
                reviewItemId,
                expectedRevision);
        if (affected != 1) {
            ReviewItem current = findItem(
                    tenant, reviewItemId)
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "ReviewItem does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "review-item",
                        reviewItemId,
                        expectedRevision);
            }
            throw new IllegalStateException(
                    "ReviewItem is already decided");
        }
        return findItem(tenant, reviewItemId).orElseThrow();
    }

    @Override
    public ReviewCampaign incrementDecidedCount(
            TenantContext tenant,
            UUID campaignId,
            Instant now) {
        int affected = jdbc.update("""
                UPDATE governance.review_campaign
                SET decided_item_count =
                        decided_item_count + 1,
                    lifecycle_state =
                        CASE
                            WHEN decided_item_count + 1
                                 = generated_item_count
                                THEN 'COMPLETED'
                            ELSE lifecycle_state
                        END,
                    completed_at =
                        CASE
                            WHEN decided_item_count + 1
                                 = generated_item_count
                                THEN ?
                            ELSE completed_at
                        END,
                    revision = revision + 1,
                    updated_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND lifecycle_state IN ('ACTIVE','COMPLETED')
                  AND decided_item_count < generated_item_count
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                campaignId);
        if (affected != 1) {
            throw new IllegalStateException(
                    "ReviewCampaign decision count could not advance");
        }
        return findCampaign(tenant, campaignId).orElseThrow();
    }

    @Override
    public void insertRemediation(
            TenantContext tenant,
            ReviewRemediation remediation) {
        jdbc.update("""
                INSERT INTO governance.review_remediation (
                    id, tenant_id, review_item_id,
                    access_assignment_id, lifecycle_state,
                    result_code, resulting_access_state,
                    revision, created_at, updated_at,
                    completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                remediation.id(),
                tenant.tenantId(),
                remediation.reviewItemId(),
                remediation.accessAssignmentId(),
                remediation.state().name(),
                remediation.resultCode(),
                remediation.resultingAccessState(),
                remediation.revision(),
                Timestamp.from(remediation.createdAt()),
                Timestamp.from(remediation.updatedAt()),
                timestamp(remediation.completedAt()));
    }

    @Override
    public Optional<ReviewRemediation> findRemediation(
            TenantContext tenant,
            UUID remediationId) {
        return jdbc.query("""
                SELECT id, review_item_id,
                       access_assignment_id,
                       lifecycle_state, result_code,
                       resulting_access_state, revision,
                       created_at, updated_at, completed_at
                FROM governance.review_remediation
                WHERE tenant_id = ? AND id = ?
                """,
                (rs,row) -> remediation(rs),
                tenant.tenantId(),
                remediationId)
                .stream()
                .findFirst();
    }

    @Override
    public ReviewRemediation completeRemediation(
            TenantContext tenant,
            UUID remediationId,
            RemediationState state,
            String resultCode,
            String resultingAccessState,
            long expectedRevision,
            Instant now) {
        if (state == RemediationState.PENDING) {
            throw new IllegalArgumentException(
                    "completion state must be terminal");
        }
        int affected = jdbc.update("""
                UPDATE governance.review_remediation
                SET lifecycle_state = ?,
                    result_code = ?,
                    resulting_access_state = ?,
                    revision = revision + 1,
                    updated_at = ?,
                    completed_at = ?
                WHERE tenant_id = ?
                  AND id = ?
                  AND lifecycle_state = 'PENDING'
                  AND revision = ?
                """,
                state.name(),
                resultCode,
                resultingAccessState,
                Timestamp.from(now),
                Timestamp.from(now),
                tenant.tenantId(),
                remediationId,
                expectedRevision);
        if (affected != 1) {
            ReviewRemediation current =
                    findRemediation(
                            tenant, remediationId)
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "ReviewRemediation does not exist"));
            if (current.revision() != expectedRevision) {
                throw new StaleWriteException(
                        "review-remediation",
                        remediationId,
                        expectedRevision);
            }
            return current;
        }
        return findRemediation(
                tenant, remediationId).orElseThrow();
    }

    private void requireRevision(
            TenantContext tenant,
            UUID campaignId,
            long expectedRevision) {
        ReviewCampaign current =
                findCampaign(tenant, campaignId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "ReviewCampaign does not exist"));
        if (current.revision() != expectedRevision) {
            throw new StaleWriteException(
                    "review-campaign",
                    campaignId,
                    expectedRevision);
        }
    }

    private static ReviewCampaign campaign(
            ResultSet rs) throws SQLException {
        return new ReviewCampaign(
                rs.getObject("id", UUID.class),
                CampaignKind.valueOf(
                        rs.getString("campaign_kind")),
                rs.getObject(
                        "subject_identity_id", UUID.class),
                rs.getObject(
                        "reviewer_identity_id", UUID.class),
                rs.getTimestamp("snapshot_at").toInstant(),
                CampaignState.valueOf(
                        rs.getString("lifecycle_state")),
                instant(rs.getTimestamp(
                        "generation_after_created_at")),
                rs.getObject(
                        "generation_after_id", UUID.class),
                rs.getLong("generated_item_count"),
                rs.getLong("decided_item_count"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("activated_at")),
                instant(rs.getTimestamp("completed_at")),
                rs.getString("failure_code"));
    }

    private static ReviewItem item(
            ResultSet rs) throws SQLException {
        return new ReviewItem(
                rs.getObject("id", UUID.class),
                rs.getObject(
                        "review_campaign_id", UUID.class),
                rs.getObject(
                        "reviewer_identity_id", UUID.class),
                rs.getObject(
                        "access_assignment_id", UUID.class),
                rs.getLong("assignment_revision"),
                rs.getString("target_kind"),
                rs.getObject("role_id", UUID.class),
                rs.getObject("entitlement_id", UUID.class),
                rs.getString(
                        "principal_constraint_kind"),
                rs.getObject(
                        "specific_principal_id", UUID.class),
                rs.getString("provenance_kind"),
                rs.getObject(
                        "provenance_ref_id", UUID.class),
                rs.getString(
                        "snapshot_lifecycle_state"),
                instant(rs.getTimestamp("valid_from")),
                instant(rs.getTimestamp("valid_until")),
                rs.getTimestamp(
                        "assignment_created_at").toInstant(),
                rs.getTimestamp("snapshot_at").toInstant(),
                ItemState.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static ReviewRemediation remediation(
            ResultSet rs) throws SQLException {
        return new ReviewRemediation(
                rs.getObject("id", UUID.class),
                rs.getObject(
                        "review_item_id", UUID.class),
                rs.getObject(
                        "access_assignment_id", UUID.class),
                RemediationState.valueOf(
                        rs.getString("lifecycle_state")),
                rs.getString("result_code"),
                rs.getString(
                        "resulting_access_state"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("completed_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null
                ? null
                : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null
                ? null
                : value.toInstant();
    }
}
