package io.wyrmgate.iam.governance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessFactSink;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Durable cross-capability consumer for Integration observed-access input facts.
 *
 * <p>The Integration fact is committed with observation/mapping state first.
 * Governance claims it later and evaluates findings in a separate transaction.
 * Retry therefore cannot roll back valid Integration state.</p>
 */
public final class GovernanceObservationProcessingService {

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 50;
    private static final String INVALID_FACT = "governance_observation_fact_invalid";
    private static final String EVALUATION_FAILED = "governance_observation_evaluation_failed";

    private final JdbcOutboxRepository outbox;
    private final ObservedAccessDriftEvaluationService drift;
    private final ObjectMapper json;
    private final Clock clock;

    public GovernanceObservationProcessingService(
            JdbcOutboxRepository outbox,
            ObservedAccessDriftEvaluationService drift,
            ObjectMapper json) {
        this(outbox, drift, json, Clock.systemUTC());
    }

    GovernanceObservationProcessingService(
            JdbcOutboxRepository outbox,
            ObservedAccessDriftEvaluationService drift,
            ObjectMapper json,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.drift = Objects.requireNonNull(drift, "drift");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ProcessingBatchResult processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(IntegrationObservedAccessFactSink.OBSERVED_ACCESS_INPUT_CHANGED),
                now,
                CLAIM_LEASE,
                BATCH_SIZE);

        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                UUID bindingId = bindingId(item);
                Instant evaluatedAt = clock.instant();
                drift.evaluate(item.tenant(), bindingId, evaluatedAt);
                outbox.markPublished(
                        item.tenant(), item.event().eventId(), evaluatedAt);
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(), item.event().eventId(), INVALID_FACT);
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        EVALUATION_FAILED);
                failed++;
            }
        }
        return new ProcessingBatchResult(claimed.size(), processed, failed);
    }

    private UUID bindingId(ClaimedOutboxEvent item) {
        var event = item.event();
        if (event.eventVersion() != 1) {
            throw new IllegalArgumentException("unsupported observed-access fact version");
        }
        if (!Set.of("reconciliation-run", "entitlement-observation-mapping")
                .contains(event.aggregateType())) {
            throw new IllegalArgumentException("unsupported observed-access fact aggregate");
        }
        try {
            JsonNode root = json.readTree(event.payloadJson());
            JsonNode value = root.get("connectorBindingId");
            if (value == null || !value.isTextual()) {
                throw new IllegalArgumentException(
                        "observed-access fact requires connectorBindingId");
            }
            return UUID.fromString(value.textValue());
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException(
                    "observed-access fact payload is invalid JSON", invalidJson);
        }
    }

    public record ProcessingBatchResult(int claimed, int processed, int failed) {
        public ProcessingBatchResult {
            if (claimed < 0 || processed < 0 || failed < 0
                    || processed + failed != claimed) {
                throw new IllegalArgumentException(
                        "invalid observation processing batch counts");
            }
        }
    }
}
