package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ReviewModels.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository {

    void insertCampaign(TenantContext tenant, ReviewCampaign campaign);
    Optional<ReviewCampaign> findCampaign(TenantContext tenant, UUID campaignId);

    List<ReviewCampaign> findCampaignPage(
            TenantContext tenant,
            Instant afterCreatedAt,
            UUID afterId,
            int limit);

    ReviewCampaign startGeneration(
            TenantContext tenant,
            UUID campaignId,
            long expectedRevision,
            Instant now);

    ReviewCampaign recordGenerationPage(
            TenantContext tenant,
            UUID campaignId,
            Instant nextCreatedAt,
            UUID nextId,
            int insertedCount,
            boolean complete,
            long expectedRevision,
            Instant now);

    boolean insertItemIfAbsent(TenantContext tenant, ReviewItem item);
    Optional<ReviewItem> findItem(TenantContext tenant, UUID reviewItemId);

    List<ReviewItem> findCampaignItemPage(
            TenantContext tenant,
            UUID campaignId,
            Instant afterCreatedAt,
            UUID afterId,
            int limit);

    List<ReviewItem> findReviewerPendingPage(
            TenantContext tenant,
            UUID reviewerIdentityId,
            Instant afterCreatedAt,
            UUID afterId,
            int limit);

    Optional<ReviewDecision> findDecisionByItem(
            TenantContext tenant,
            UUID reviewItemId);

    Optional<ReviewRemediation> findRemediationByItem(
            TenantContext tenant,
            UUID reviewItemId);

    void insertDecision(TenantContext tenant, ReviewDecision decision);

    ReviewItem markItemDecided(
            TenantContext tenant,
            UUID reviewItemId,
            long expectedRevision,
            Instant now);

    ReviewCampaign incrementDecidedCount(
            TenantContext tenant,
            UUID campaignId,
            Instant now);

    void insertRemediation(
            TenantContext tenant,
            ReviewRemediation remediation);

    Optional<ReviewRemediation> findRemediation(
            TenantContext tenant,
            UUID remediationId);

    ReviewRemediation completeRemediation(
            TenantContext tenant,
            UUID remediationId,
            RemediationState state,
            String resultCode,
            String resultingAccessState,
            long expectedRevision,
            Instant now);
}
