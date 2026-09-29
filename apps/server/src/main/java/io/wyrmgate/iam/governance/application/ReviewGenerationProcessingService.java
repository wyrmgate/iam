package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.AccessReviewSnapshotQuery;
import io.wyrmgate.iam.governance.domain.ReviewModels.*;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ReviewGenerationProcessingService {

    private static final Duration CLAIM_LEASE =
            Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY =
            Duration.ofSeconds(5);
    private static final int CLAIM_BATCH = 20;
    private static final int PAGE_SIZE = 200;

    private final JdbcOutboxRepository outbox;
    private final ReviewRepository reviews;
    private final AccessReviewSnapshotQuery access;
    private final ReviewWorkSink work;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public ReviewGenerationProcessingService(
            JdbcOutboxRepository outbox,
            ReviewRepository reviews,
            AccessReviewSnapshotQuery access,
            ReviewWorkSink work,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this(
                outbox,
                reviews,
                access,
                work,
                ids,
                transactions,
                Clock.systemUTC());
    }

    ReviewGenerationProcessingService(
            JdbcOutboxRepository outbox,
            ReviewRepository reviews,
            AccessReviewSnapshotQuery access,
            ReviewWorkSink work,
            IdGenerator ids,
            TransactionExecutor transactions,
            Clock clock) {
        this.outbox = Objects.requireNonNull(
                outbox, "outbox");
        this.reviews = Objects.requireNonNull(
                reviews, "reviews");
        this.access = Objects.requireNonNull(
                access, "access");
        this.work = Objects.requireNonNull(
                work, "work");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
        this.clock = Objects.requireNonNull(
                clock, "clock");
    }

    public BatchResult processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed =
                outbox.claimPending(
                        Set.of(
                                ReviewWorkSink
                                        .GENERATION_REQUESTED),
                        now,
                        CLAIM_LEASE,
                        CLAIM_BATCH);
        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent event : claimed) {
            try {
                processOne(event, clock.instant());
                outbox.markPublished(
                        event.tenant(),
                        event.event().eventId(),
                        clock.instant());
                processed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        event.tenant(),
                        event.event().eventId(),
                        clock.instant().plus(
                                RETRY_DELAY),
                        "review_generation_failed");
                failed++;
            }
        }
        return new BatchResult(
                claimed.size(), processed, failed);
    }

    private void processOne(
            ClaimedOutboxEvent claimed,
            Instant now) {
        if (claimed.event().eventVersion() != 1
                || !"review-campaign".equals(
                        claimed.event().aggregateType())) {
            throw new IllegalArgumentException(
                    "invalid review generation fact");
        }

        ReviewCampaign campaign =
                reviews.findCampaign(
                                claimed.tenant(),
                                claimed.event().aggregateId())
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "ReviewCampaign does not exist"));

        if (campaign.state()
                != CampaignState.GENERATING) {
            return;
        }

        AccessReviewSnapshotQuery.Position after =
                campaign.generationAfterCreatedAt()
                                == null
                        ? null
                        : new AccessReviewSnapshotQuery
                                .Position(
                                        campaign
                                                .generationAfterCreatedAt(),
                                        campaign
                                                .generationAfterId());

        AccessReviewSnapshotQuery.Page page =
                access.page(
                        claimed.tenant(),
                        campaign.subjectIdentityId(),
                        campaign.snapshotAt(),
                        after,
                        PAGE_SIZE);

        transactions.required(() -> {
            ReviewCampaign current =
                    reviews.findCampaign(
                                    claimed.tenant(),
                                    campaign.id())
                            .orElseThrow();
            if (current.state()
                    != CampaignState.GENERATING) {
                return null;
            }
            if (!Objects.equals(
                            current
                                    .generationAfterCreatedAt(),
                            campaign
                                    .generationAfterCreatedAt())
                    || !Objects.equals(
                            current.generationAfterId(),
                            campaign.generationAfterId())) {
                return null;
            }

            int inserted = 0;
            for (AccessReviewSnapshotQuery.Item snapshot
                    : page.items()) {
                ReviewItem item = new ReviewItem(
                        ids.nextId(),
                        current.id(),
                        current.reviewerIdentityId(),
                        snapshot.accessAssignmentId(),
                        snapshot.assignmentRevision(),
                        snapshot.targetKind(),
                        snapshot.roleId(),
                        snapshot.entitlementId(),
                        snapshot.principalConstraintKind(),
                        snapshot.specificPrincipalId(),
                        snapshot.provenanceKind(),
                        snapshot.provenanceRefId(),
                        snapshot.lifecycleState(),
                        snapshot.validFrom(),
                        snapshot.validUntil(),
                        snapshot.createdAt(),
                        current.snapshotAt(),
                        ItemState.PENDING,
                        1,
                        now,
                        now);
                if (reviews.insertItemIfAbsent(
                        claimed.tenant(), item)) {
                    inserted++;
                }
            }

            boolean complete =
                    page.nextPosition() == null;
            ReviewCampaign updated =
                    reviews.recordGenerationPage(
                            claimed.tenant(),
                            current.id(),
                            complete
                                    ? current
                                            .generationAfterCreatedAt()
                                    : page.nextPosition()
                                            .createdAt(),
                            complete
                                    ? current
                                            .generationAfterId()
                                    : page.nextPosition().id(),
                            inserted,
                            complete,
                            current.revision(),
                            now);
            if (!complete) {
                work.generationRequested(
                        claimed.tenant(), updated);
            }
            return null;
        });
    }

    public record BatchResult(
            int claimed,
            int processed,
            int failed) {}
}
