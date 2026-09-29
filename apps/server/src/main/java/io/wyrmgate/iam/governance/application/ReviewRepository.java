package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ReviewModels.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository {

    void insertCampaign(TenantContext tenant, ReviewCampaign campaign);
    Optional<ReviewCampaign> findCampaign(TenantContext tenant, UUID campaignId);

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
