package io.wyrmgate.iam.governance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.governance.application.GovernanceObservationEvaluationRepository.Phase;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessFactSink;
import io.wyrmgate.iam.integration.application.IntegrationObservedAccessQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One bounded page per claim; checkpoint, findings, continuation and acknowledgement commit together. */
public final class GovernanceObservationProcessingService {
    public static final String CONTINUATION = "governance.observation-evaluation-page-requested";
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 50;

    private final JdbcOutboxRepository outbox;
    private final ObservedAccessDriftEvaluationService drift;
    private final IntegrationObservedAccessQuery observedAccess;
    private final GovernanceObservationEvaluationRepository checkpoints;
    private final TransactionExecutor transactions;
    private final IdGenerator ids;
    private final ObjectMapper json;
    private final Clock clock;

    public GovernanceObservationProcessingService(
            JdbcOutboxRepository outbox, ObservedAccessDriftEvaluationService drift,
            IntegrationObservedAccessQuery observedAccess,
            GovernanceObservationEvaluationRepository checkpoints,
            TransactionExecutor transactions, IdGenerator ids, ObjectMapper json) {
        this(outbox, drift, observedAccess, checkpoints, transactions, ids, json, Clock.systemUTC());
    }

    GovernanceObservationProcessingService(
            JdbcOutboxRepository outbox, ObservedAccessDriftEvaluationService drift,
            IntegrationObservedAccessQuery observedAccess,
            GovernanceObservationEvaluationRepository checkpoints,
            TransactionExecutor transactions, IdGenerator ids, ObjectMapper json, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox);
        this.drift = Objects.requireNonNull(drift);
        this.observedAccess = Objects.requireNonNull(observedAccess);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.transactions = Objects.requireNonNull(transactions);
        this.ids = Objects.requireNonNull(ids);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
    }

    public ProcessingBatchResult processAvailable() {
        var claimed = outbox.claimPending(Set.of(
                IntegrationObservedAccessFactSink.OBSERVED_ACCESS_INPUT_CHANGED, CONTINUATION),
                clock.instant(), CLAIM_LEASE, BATCH_SIZE);
        int processed = 0;
        int failed = 0;
        for (var item : claimed) {
            try {
                UUID binding = bindingId(item);
                transactions.required(() -> processPage(item, binding, clock.instant()));
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(item.tenant(), item.event().eventId(),
                        "governance_observation_fact_invalid");
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(item.tenant(), item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY), "governance_observation_evaluation_failed");
                failed++;
            }
        }
        return new ProcessingBatchResult(claimed.size(), processed, failed);
    }

    private void processPage(ClaimedOutboxEvent item, UUID binding, Instant now) {
        long generation = observedAccess.currentGeneration(item.tenant(), binding);
        var checkpoint = checkpoints.lockOrStart(item.tenant(), binding, generation, now);
        if (checkpoint.phase() != Phase.COMPLETED) {
            var progress = drift.evaluatePage(item.tenant(), binding, checkpoint.phase(),
                    checkpoint.afterKey(), now);
            if (observedAccess.currentGeneration(item.tenant(), binding) != generation) {
                throw new IllegalStateException("observation input changed during evaluation");
            }
            var next = checkpoints.advance(item.tenant(), binding, checkpoint,
                    progress.phase(), progress.afterKey(), now);
            if (next.phase() != Phase.COMPLETED) {
                UUID nextId = ids.nextId();
                outbox.append(item.tenant(), new OutboxEvent(nextId, CONTINUATION, 1,
                        "governance-observation-evaluation", binding, next.revision(), now,
                        item.event().correlationId(), item.event().eventId(),
                        "{\"connectorBindingId\":\"" + binding + "\"}"), now);
            }
        }
        outbox.markPublished(item.tenant(), item.event().eventId(), now);
    }

    private UUID bindingId(ClaimedOutboxEvent item) {
        var event = item.event();
        if (event.eventVersion() != 1) throw new IllegalArgumentException("unsupported fact version");
        if (CONTINUATION.equals(event.eventType())) {
            if (!"governance-observation-evaluation".equals(event.aggregateType())
                    || event.aggregateId() == null) throw new IllegalArgumentException("invalid continuation");
            return event.aggregateId();
        }
        if (event.aggregateType() == null || !Set.of("reconciliation-run",
                "entitlement-observation-mapping", "principal-observation-mapping")
                .contains(event.aggregateType())) throw new IllegalArgumentException("invalid observation fact");
        try {
            var root = json.readTree(event.payloadJson());
            var field = root == null ? null : root.get("connectorBindingId");
            if (field == null || !field.isTextual()) throw new IllegalArgumentException("missing binding");
            return UUID.fromString(field.textValue());
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("invalid observation fact JSON", invalidJson);
        }
    }

    public record ProcessingBatchResult(int claimed, int processed, int failed) {
        public ProcessingBatchResult {
            if (claimed < 0 || processed < 0 || failed < 0 || processed + failed != claimed) {
                throw new IllegalArgumentException("invalid observation processing batch counts");
            }
        }
    }
}
