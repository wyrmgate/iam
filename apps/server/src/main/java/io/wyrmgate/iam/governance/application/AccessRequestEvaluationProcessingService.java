package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemState;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AccessRequestEvaluationProcessingService {

    private static final Duration CLAIM_LEASE =
            Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY =
            Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 50;
    private static final String INVALID_FACT =
            "request_item_submitted_fact_invalid";
    private static final String EVALUATION_UNAVAILABLE =
            "request_item_evaluation_unavailable";
    private static final String EVALUATION_FAILED =
            "request_item_evaluation_failed";

    private final JdbcOutboxRepository outbox;
    private final AccessRequestRepository requests;
    private final AccessRequestCommandService commands;
    private final Clock clock;

    public AccessRequestEvaluationProcessingService(
            JdbcOutboxRepository outbox,
            AccessRequestRepository requests,
            AccessRequestCommandService commands) {
        this(outbox, requests, commands, Clock.systemUTC());
    }

    AccessRequestEvaluationProcessingService(
            JdbcOutboxRepository outbox,
            AccessRequestRepository requests,
            AccessRequestCommandService commands,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ProcessingBatchResult processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(SubmittedRequestItemSink.REQUEST_ITEM_SUBMITTED),
                now,
                CLAIM_LEASE,
                BATCH_SIZE);

        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent claimedEvent : claimed) {
            try {
                ProcessingOutcome outcome =
                        evaluate(claimedEvent, clock.instant());
                if (outcome == ProcessingOutcome.RETRY) {
                    outbox.markFailed(
                            claimedEvent.tenant(),
                            claimedEvent.event().eventId(),
                            clock.instant().plus(RETRY_DELAY),
                            EVALUATION_UNAVAILABLE);
                    failed++;
                } else {
                    outbox.markPublished(
                            claimedEvent.tenant(),
                            claimedEvent.event().eventId(),
                            clock.instant());
                    processed++;
                }
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        claimedEvent.tenant(),
                        claimedEvent.event().eventId(),
                        INVALID_FACT);
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        claimedEvent.tenant(),
                        claimedEvent.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        EVALUATION_FAILED);
                failed++;
            }
        }
        return new ProcessingBatchResult(
                claimed.size(), processed, failed);
    }

    private ProcessingOutcome evaluate(
            ClaimedOutboxEvent claimed,
            Instant now) {
        var event = claimed.event();
        if (event.eventVersion() != 1
                || !"request-item".equals(event.aggregateType())
                || event.aggregateId() == null) {
            throw new IllegalArgumentException(
                    "unsupported RequestItem submission fact");
        }

        RequestItem item = requests.findItem(
                        claimed.tenant(),
                        event.aggregateId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "submitted RequestItem does not exist"));
        if (item.state() != ItemState.SUBMITTED
                && item.state() != ItemState.EVALUATING) {
            return ProcessingOutcome.DONE;
        }

        RequestItem evaluated = commands.evaluateItem(
                claimed.tenant(),
                item.id(),
                item.revision(),
                now);
        return evaluated.state() == ItemState.EVALUATING
                ? ProcessingOutcome.RETRY
                : ProcessingOutcome.DONE;
    }

    private enum ProcessingOutcome {
        DONE,
        RETRY
    }

    public record ProcessingBatchResult(
            int claimed,
            int processed,
            int failed) {
        public ProcessingBatchResult {
            if (claimed < 0 || processed < 0 || failed < 0
                    || processed + failed > claimed) {
                throw new IllegalArgumentException(
                        "invalid processing batch counts");
            }
        }
    }
}
