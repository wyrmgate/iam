package io.wyrmgate.iam.access.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.domain.IdentityAccessReduction;
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

public final class IdentityAccessReductionIntakeService {

    public static final String ACCESS_ELIGIBILITY_CHANGED =
            "identity.access-eligibility-changed";

    private static final Duration CLAIM_LEASE =
            Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY =
            Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 50;

    private final JdbcOutboxRepository outbox;
    private final IdentityAccessReductionRepository reductions;
    private final IdentityAccessReductionWorkSink work;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public IdentityAccessReductionIntakeService(
            JdbcOutboxRepository outbox,
            IdentityAccessReductionRepository reductions,
            IdentityAccessReductionWorkSink work,
            IdGenerator ids,
            TransactionExecutor transactions,
            ObjectMapper json) {
        this(
                outbox,
                reductions,
                work,
                ids,
                transactions,
                json,
                Clock.systemUTC());
    }

    IdentityAccessReductionIntakeService(
            JdbcOutboxRepository outbox,
            IdentityAccessReductionRepository reductions,
            IdentityAccessReductionWorkSink work,
            IdGenerator ids,
            TransactionExecutor transactions,
            ObjectMapper json,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.reductions = Objects.requireNonNull(
                reductions, "reductions");
        this.work = Objects.requireNonNull(work, "work");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BatchResult processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed =
                outbox.claimPending(
                        Set.of(ACCESS_ELIGIBILITY_CHANGED),
                        now,
                        CLAIM_LEASE,
                        BATCH_SIZE);
        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                consume(item, clock.instant());
                outbox.markPublished(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(),
                        item.event().eventId(),
                        "identity_access_reduction_fact_invalid");
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "identity_access_reduction_intake_failed");
                failed++;
            }
        }
        return new BatchResult(
                claimed.size(), processed, failed);
    }

    private void consume(
            ClaimedOutboxEvent item,
            Instant now) {
        var event = item.event();
        if (event.eventVersion() != 1
                || !"identity".equals(event.aggregateType())
                || event.aggregateId() == null
                || event.aggregateRevision() == null
                || event.aggregateRevision() < 1) {
            throw new IllegalArgumentException(
                    "unsupported Identity access-eligibility fact");
        }

        JsonNode payload = payload(event.payloadJson());
        JsonNode eligibleNode =
                payload.get("accessEligible");
        JsonNode lifecycleNode =
                payload.get("lifecycleState");
        if (eligibleNode == null
                || !eligibleNode.isBoolean()
                || lifecycleNode == null
                || !lifecycleNode.isTextual()
                || lifecycleNode.textValue().isBlank()) {
            throw new IllegalArgumentException(
                    "Identity access-eligibility fact payload is incomplete");
        }

        if (eligibleNode.booleanValue()) {
            return;
        }
        String lifecycleState =
                lifecycleNode.textValue();
        if ("ACTIVE".equals(lifecycleState)) {
            throw new IllegalArgumentException(
                    "access-ineligible fact cannot carry ACTIVE lifecycle");
        }

        IdentityAccessReduction candidate =
                new IdentityAccessReduction(
                        ids.nextId(),
                        event.aggregateId(),
                        event.aggregateRevision(),
                        lifecycleState,
                        IdentityAccessReduction.State.RUNNING,
                        event.occurredAt(),
                        null,
                        null,
                        0,
                        1,
                        now,
                        now,
                        null);

        transactions.required(() -> {
            IdentityAccessReduction current =
                    reductions.startIfAbsent(
                            item.tenant(), candidate);
            if (current.id().equals(candidate.id())) {
                work.reductionRequested(
                        item.tenant(),
                        current,
                        event.correlationId(),
                        event.eventId());
            }
            return null;
        });
    }

    private JsonNode payload(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException(
                    "Identity access-eligibility fact payload is invalid JSON",
                    invalidJson);
        }
    }

    public record BatchResult(
            int claimed,
            int processed,
            int failed) {
        public BatchResult {
            if (claimed < 0
                    || processed < 0
                    || failed < 0
                    || processed + failed != claimed) {
                throw new IllegalArgumentException(
                        "invalid Identity reduction intake batch counts");
            }
        }
    }
}
