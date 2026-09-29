package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.AccessReviewRemediationCommand;
import io.wyrmgate.iam.governance.domain.ReviewModels.RemediationState;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ReviewRemediationProcessingService {

    private static final Duration CLAIM_LEASE =
            Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY =
            Duration.ofSeconds(5);
    private static final int CLAIM_BATCH = 50;

    private final JdbcOutboxRepository outbox;
    private final ReviewRepository reviews;
    private final AccessReviewRemediationCommand access;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public ReviewRemediationProcessingService(
            JdbcOutboxRepository outbox,
            ReviewRepository reviews,
            AccessReviewRemediationCommand access,
            TransactionExecutor transactions) {
        this(
                outbox,
                reviews,
                access,
                transactions,
                Clock.systemUTC());
    }

    ReviewRemediationProcessingService(
            JdbcOutboxRepository outbox,
            ReviewRepository reviews,
            AccessReviewRemediationCommand access,
            TransactionExecutor transactions,
            Clock clock) {
        this.outbox = Objects.requireNonNull(
                outbox, "outbox");
        this.reviews = Objects.requireNonNull(
                reviews, "reviews");
        this.access = Objects.requireNonNull(
                access, "access");
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
                                        .REMEDIATION_REQUESTED),
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
                        "review_remediation_failed");
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
                || !"review-remediation".equals(
                        claimed.event().aggregateType())) {
            throw new IllegalArgumentException(
                    "invalid review remediation fact");
        }

        var remediation =
                reviews.findRemediation(
                                claimed.tenant(),
                                claimed.event().aggregateId())
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "ReviewRemediation does not exist"));
        if (remediation.state()
                != RemediationState.PENDING) {
            return;
        }

        AccessReviewRemediationCommand.Result result =
                access.apply(
                        claimed.tenant(),
                        remediation.id(),
                        remediation.accessAssignmentId(),
                        now);

        transactions.required(() -> {
            var current = reviews.findRemediation(
                            claimed.tenant(),
                            remediation.id())
                    .orElseThrow();
            if (current.state()
                    != RemediationState.PENDING) {
                return null;
            }
            RemediationState state =
                    result.outcome()
                                    == AccessReviewRemediationCommand
                                            .Outcome.APPLIED
                            ? RemediationState.APPLIED
                            : RemediationState
                                    .NO_ACTION_REQUIRED;
            reviews.completeRemediation(
                    claimed.tenant(),
                    current.id(),
                    state,
                    result.outcome().name()
                            .toLowerCase(),
                    result.resultingLifecycleState(),
                    current.revision(),
                    now);
            return null;
        });
    }

    public record BatchResult(
            int claimed,
            int processed,
            int failed) {}
}
