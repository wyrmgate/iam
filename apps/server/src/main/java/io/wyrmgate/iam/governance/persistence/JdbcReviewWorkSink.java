package io.wyrmgate.iam.governance.persistence;

import io.wyrmgate.iam.governance.application.ReviewWorkSink;
import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewCampaign;
import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewRemediation;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;

public final class JdbcReviewWorkSink
        implements ReviewWorkSink {

    private final JdbcOutboxRepository outbox;
    private final IdGenerator ids;

    public JdbcReviewWorkSink(
            JdbcOutboxRepository outbox,
            IdGenerator ids) {
        this.outbox = Objects.requireNonNull(
                outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public void generationRequested(
            TenantContext tenant,
            ReviewCampaign campaign) {
        append(
                tenant,
                GENERATION_REQUESTED,
                "review-campaign",
                campaign.id(),
                campaign.revision(),
                campaign.updatedAt());
    }

    @Override
    public void remediationRequested(
            TenantContext tenant,
            ReviewRemediation remediation) {
        append(
                tenant,
                REMEDIATION_REQUESTED,
                "review-remediation",
                remediation.id(),
                remediation.revision(),
                remediation.updatedAt());
    }

    private void append(
            TenantContext tenant,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            long revision,
            java.time.Instant now) {
        UUID eventId = ids.nextId();
        outbox.append(
                tenant,
                new OutboxEvent(
                        eventId,
                        eventType,
                        1,
                        aggregateType,
                        aggregateId,
                        revision,
                        now,
                        eventId,
                        null,
                        "{}"),
                now);
    }
}
