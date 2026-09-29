package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ReviewModels.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReviewQueryModels {
    private ReviewQueryModels() {}

    public record Position(Instant createdAt, UUID id) {}

    public record CampaignPage(
            List<ReviewCampaign> items,
            Position nextPosition) {
        public CampaignPage {
            items = List.copyOf(items);
        }
    }

    public record ItemPage(
            List<ReviewItem> items,
            Position nextPosition) {
        public ItemPage {
            items = List.copyOf(items);
        }
    }

    public record ItemEvidence(
            ReviewItem item,
            ReviewDecision decision,
            ReviewRemediation remediation) {}

    public record RemediationEvidence(
            ReviewRemediation remediation,
            ReviewItem item,
            ReviewCampaign campaign) {}
}
