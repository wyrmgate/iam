package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.ReviewQueryModels.*;
import io.wyrmgate.iam.governance.domain.ReviewModels.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ReviewQueryService {

    private final ReviewRepository repository;

    public ReviewQueryService(ReviewRepository repository) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
    }

    public CampaignPage campaigns(
            TenantContext tenant,
            Position after,
            int limit) {
        requireLimit(limit);
        List<ReviewCampaign> rows =
                repository.findCampaignPage(
                        tenant,
                        after == null ? null : after.createdAt(),
                        after == null ? null : after.id(),
                        limit + 1);
        boolean more = rows.size() > limit;
        List<ReviewCampaign> selected =
                rows.subList(0, Math.min(limit, rows.size()));
        return new CampaignPage(
                List.copyOf(selected),
                more && !selected.isEmpty()
                        ? position(selected.getLast())
                        : null);
    }

    public ItemPage campaignItems(
            TenantContext tenant,
            UUID campaignId,
            Position after,
            int limit) {
        requireLimit(limit);
        Objects.requireNonNull(campaignId, "campaignId");
        List<ReviewItem> rows =
                repository.findCampaignItemPage(
                        tenant,
                        campaignId,
                        after == null ? null : after.createdAt(),
                        after == null ? null : after.id(),
                        limit + 1);
        return itemPage(rows, limit);
    }

    public ItemPage reviewerInbox(
            TenantContext tenant,
            UUID reviewerIdentityId,
            Position after,
            int limit) {
        requireLimit(limit);
        Objects.requireNonNull(
                reviewerIdentityId, "reviewerIdentityId");
        List<ReviewItem> rows =
                repository.findReviewerPendingPage(
                        tenant,
                        reviewerIdentityId,
                        after == null ? null : after.createdAt(),
                        after == null ? null : after.id(),
                        limit + 1);
        return itemPage(rows, limit);
    }

    public Optional<ItemEvidence> itemEvidence(
            TenantContext tenant,
            UUID reviewItemId) {
        return repository.findItem(tenant, reviewItemId)
                .map(item -> new ItemEvidence(
                        item,
                        repository.findDecisionByItem(
                                tenant, item.id()).orElse(null),
                        repository.findRemediationByItem(
                                tenant, item.id()).orElse(null)));
    }

    public Optional<RemediationEvidence> remediationEvidence(
            TenantContext tenant,
            UUID remediationId) {
        return repository.findRemediation(
                        tenant, remediationId)
                .flatMap(remediation ->
                        repository.findItem(
                                        tenant,
                                        remediation.reviewItemId())
                                .flatMap(item ->
                                        repository.findCampaign(
                                                        tenant,
                                                        item.reviewCampaignId())
                                                .map(campaign ->
                                                        new RemediationEvidence(
                                                                remediation,
                                                                item,
                                                                campaign))));
    }

    private static ItemPage itemPage(
            List<ReviewItem> rows, int limit) {
        boolean more = rows.size() > limit;
        List<ReviewItem> selected =
                rows.subList(0, Math.min(limit, rows.size()));
        return new ItemPage(
                List.copyOf(selected),
                more && !selected.isEmpty()
                        ? position(selected.getLast())
                        : null);
    }

    private static Position position(
            ReviewCampaign value) {
        return new Position(
                value.createdAt(), value.id());
    }

    private static Position position(
            ReviewItem value) {
        return new Position(
                value.createdAt(), value.id());
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 200");
        }
    }
}
