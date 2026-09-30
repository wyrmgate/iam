package io.wyrmgate.iam.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.SourceAbsenceInference;
import io.wyrmgate.iam.identity.domain.SourceAbsencePolicyVersion;
import io.wyrmgate.iam.identity.domain.SourceAbsenceTrust;
import io.wyrmgate.iam.identity.domain.SourceImportCompleteness;
import io.wyrmgate.iam.identity.domain.SourceImportRun;
import io.wyrmgate.iam.identity.domain.SourceRecord;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Durable COMPLETE-gated source absence inference.
 *
 * <p>Only explicit run-scoped TRUSTED coverage may enter this process. Inferred absence is
 * deliberately reversible and maps only to Identity INACTIVE.</p>
 */
public final class SourceAbsenceInferenceService {

    public static final String SOURCE_IMPORT_COMPLETED = "identity.source-import-completed";

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int CLAIM_BATCH = 20;
    private static final int PAGE_SIZE = 50;

    private final JdbcOutboxRepository outbox;
    private final SourceCorrelationRepository sources;
    private final IdentityRepository identities;
    private final IdentityCommandService identityCommands;
    private final SourceAbsenceInferenceWorkSink work;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public SourceAbsenceInferenceService(
            JdbcOutboxRepository outbox,
            SourceCorrelationRepository sources,
            IdentityRepository identities,
            IdentityCommandService identityCommands,
            SourceAbsenceInferenceWorkSink work,
            IdGenerator ids,
            TransactionExecutor transactions,
            ObjectMapper json) {
        this(
                outbox,
                sources,
                identities,
                identityCommands,
                work,
                ids,
                transactions,
                json,
                Clock.systemUTC());
    }

    SourceAbsenceInferenceService(
            JdbcOutboxRepository outbox,
            SourceCorrelationRepository sources,
            IdentityRepository identities,
            IdentityCommandService identityCommands,
            SourceAbsenceInferenceWorkSink work,
            IdGenerator ids,
            TransactionExecutor transactions,
            ObjectMapper json,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.identityCommands = Objects.requireNonNull(identityCommands, "identityCommands");
        this.work = Objects.requireNonNull(work, "work");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BatchResult processAvailable() {
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(SOURCE_IMPORT_COMPLETED, SourceAbsenceInferenceWorkSink.INFERENCE_REQUESTED),
                clock.instant(),
                CLAIM_LEASE,
                CLAIM_BATCH);
        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                consume(item, clock.instant());
                outbox.markPublished(item.tenant(), item.event().eventId(), clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(),
                        item.event().eventId(),
                        "source_absence_inference_fact_invalid");
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "source_absence_inference_failed");
                failed++;
            }
        }
        return new BatchResult(claimed.size(), processed, failed);
    }

    private void consume(ClaimedOutboxEvent item, Instant now) {
        var event = item.event();
        if (event.eventVersion() != 1) {
            throw new IllegalArgumentException("unsupported source absence fact version");
        }
        UUID correlationId = event.correlationId() == null ? event.eventId() : event.correlationId();
        if (SOURCE_IMPORT_COMPLETED.equals(event.eventType())) {
            UUID runId = requiredUuid(payload(event.payloadJson()), "runId");
            intake(item.tenant(), runId, correlationId, event.eventId(), now);
            return;
        }
        if (SourceAbsenceInferenceWorkSink.INFERENCE_REQUESTED.equals(event.eventType())) {
            if (!"source-absence-inference".equals(event.aggregateType())
                    || event.aggregateId() == null
                    || event.aggregateRevision() == null) {
                throw new IllegalArgumentException("invalid source absence work envelope");
            }
            processWork(
                    item.tenant(),
                    event.aggregateId(),
                    event.aggregateRevision(),
                    correlationId,
                    event.eventId(),
                    now);
            return;
        }
        throw new IllegalArgumentException("unsupported source absence fact");
    }

    private void intake(
            TenantContext tenant,
            UUID runId,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        transactions.required(() -> {
            SourceImportRun run = sources.findImportRun(tenant, runId)
                    .orElseThrow(() -> new IllegalArgumentException("source import run does not exist"));
            if (run.completeness() != SourceImportCompleteness.COMPLETE
                    || sources.findImportAbsenceTrust(tenant, runId) != SourceAbsenceTrust.TRUSTED) {
                return null;
            }
            SourceAbsencePolicyVersion policy =
                    sources.findActiveAbsencePolicy(tenant, run.sourceSystemId()).orElse(null);
            if (policy == null) {
                return null;
            }
            SourceAbsenceInference candidate = new SourceAbsenceInference(
                    ids.nextId(),
                    run.sourceSystemId(),
                    run.id(),
                    policy.id(),
                    policy.maxInferredTransitions(),
                    SourceAbsenceInference.State.RUNNING,
                    null,
                    null,
                    0,
                    0,
                    1,
                    now,
                    now,
                    null);
            SourceAbsenceInference current =
                    sources.startAbsenceInferenceIfAbsent(tenant, candidate);
            if (current.id().equals(candidate.id())) {
                work.inferenceRequested(tenant, current, correlationId, causationId);
            }
            return null;
        });
    }

    private void processWork(
            TenantContext tenant,
            UUID inferenceId,
            long eventRevision,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        SourceAbsenceInference inference = sources.findAbsenceInferenceById(tenant, inferenceId)
                .orElseThrow(() -> new IllegalArgumentException("source absence inference does not exist"));
        if (inference.state() != SourceAbsenceInference.State.RUNNING
                || eventRevision < inference.revision()) {
            return;
        }
        if (eventRevision > inference.revision()) {
            throw new IllegalStateException("source absence work is ahead of authoritative process state");
        }

        SourceImportRun run = sources.findImportRun(tenant, inference.importRunId())
                .orElseThrow(() -> new IllegalArgumentException("source import run does not exist"));
        if (!stillAuthoritative(tenant, inference, run)) {
            terminate(
                    tenant,
                    inference,
                    SourceAbsenceInference.State.SUPERSEDED,
                    now);
            return;
        }

        List<SourceRecord> page = sources.findAbsentSourceRecordPage(
                tenant,
                inference.sourceSystemId(),
                inference.importRunId(),
                run.startedAt(),
                inference.afterFirstObservedAt(),
                inference.afterSourceRecordId(),
                PAGE_SIZE + 1);
        boolean hasMore = page.size() > PAGE_SIZE;
        List<SourceRecord> selected =
                hasMore ? List.copyOf(page.subList(0, PAGE_SIZE)) : List.copyOf(page);

        if (selected.isEmpty()) {
            terminate(tenant, inference, SourceAbsenceInference.State.COMPLETED, now);
            return;
        }

        SourceAbsenceInference current = inference;
        for (SourceRecord candidate : selected) {
            current = processCandidate(
                    tenant,
                    current.id(),
                    run,
                    candidate,
                    correlationId,
                    causationId,
                    now);
            if (current.state() != SourceAbsenceInference.State.RUNNING) {
                return;
            }
        }

        if (!hasMore) {
            terminate(tenant, current, SourceAbsenceInference.State.COMPLETED, now);
            return;
        }
        work.inferenceRequested(tenant, current, correlationId, causationId);
    }

    private SourceAbsenceInference processCandidate(
            TenantContext tenant,
            UUID inferenceId,
            SourceImportRun run,
            SourceRecord candidate,
            UUID correlationId,
            UUID causationId,
            Instant now) {
        return transactions.required(() -> {
            SourceAbsenceInference current = sources.findAbsenceInferenceById(tenant, inferenceId)
                    .orElseThrow();
            if (current.state() != SourceAbsenceInference.State.RUNNING) {
                return current;
            }
            if (!stillAuthoritative(tenant, current, run)) {
                return sources.recordAbsenceInferenceProgress(
                        tenant,
                        current.id(),
                        current.afterFirstObservedAt(),
                        current.afterSourceRecordId(),
                        0,
                        0,
                        SourceAbsenceInference.State.SUPERSEDED,
                        current.revision(),
                        now);
            }

            SourceRecord latest = sources.findSourceRecordForUpdate(tenant, candidate.id())
                    .orElseThrow(() -> new IllegalArgumentException("source record does not exist"));
            boolean absentForRun = !latest.lastImportRunId().equals(run.id())
                    && latest.lastObservedAt().isBefore(run.startedAt());
            long transitionDelta = 0;

            if (absentForRun) {
                var link = sources.findActiveAcceptedLink(tenant, latest.id());
                if (link.isPresent() && !link.get().linkedAt().isAfter(run.startedAt())) {
                    Identity identity = identities.findById(tenant, link.get().identityId())
                            .orElseThrow(() -> new IllegalArgumentException("linked identity does not exist"));
                    boolean needsTransition = identity.lifecycleState() == IdentityLifecycleState.PENDING
                            || identity.lifecycleState() == IdentityLifecycleState.ACTIVE
                            || identity.lifecycleState() == IdentityLifecycleState.SUSPENDED;
                    if (needsTransition) {
                        if (current.inferredTransitionCount() >= current.maxInferredTransitions()) {
                            return sources.recordAbsenceInferenceProgress(
                                    tenant,
                                    current.id(),
                                    current.afterFirstObservedAt(),
                                    current.afterSourceRecordId(),
                                    0,
                                    0,
                                    SourceAbsenceInference.State.MANUAL_REQUIRED,
                                    current.revision(),
                                    now);
                        }
                        Instant transitionTime =
                                now.isBefore(identity.updatedAt()) ? identity.updatedAt() : now;
                        identityCommands.changeLifecycle(
                                tenant,
                                identity.id(),
                                IdentityLifecycleState.INACTIVE,
                                identity.revision(),
                                transitionTime,
                                correlationId,
                                causationId);
                        transitionDelta = 1;
                    }
                }
            }

            return sources.recordAbsenceInferenceProgress(
                    tenant,
                    current.id(),
                    candidate.firstObservedAt(),
                    candidate.id(),
                    1,
                    transitionDelta,
                    SourceAbsenceInference.State.RUNNING,
                    current.revision(),
                    now);
        });
    }

    private boolean stillAuthoritative(
            TenantContext tenant,
            SourceAbsenceInference inference,
            SourceImportRun run) {
        if (run.completeness() != SourceImportCompleteness.COMPLETE
                || sources.findImportAbsenceTrust(tenant, run.id()) != SourceAbsenceTrust.TRUSTED) {
            return false;
        }
        SourceAbsencePolicyVersion currentPolicy =
                sources.findActiveAbsencePolicy(tenant, inference.sourceSystemId()).orElse(null);
        return currentPolicy != null
                && currentPolicy.id().equals(inference.policyVersionId())
                && !sources.hasImportStartedAfter(
                        tenant, inference.sourceSystemId(), run.startedAt());
    }

    private void terminate(
            TenantContext tenant,
            SourceAbsenceInference inference,
            SourceAbsenceInference.State state,
            Instant now) {
        transactions.required(() -> {
            SourceAbsenceInference current = sources.findAbsenceInferenceById(tenant, inference.id())
                    .orElseThrow();
            if (current.state() == SourceAbsenceInference.State.RUNNING) {
                sources.recordAbsenceInferenceProgress(
                        tenant,
                        current.id(),
                        current.afterFirstObservedAt(),
                        current.afterSourceRecordId(),
                        0,
                        0,
                        state,
                        current.revision(),
                        now);
            }
            return null;
        });
    }

    private JsonNode payload(String value) {
        try {
            JsonNode parsed = json.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("fact payload must be an object");
            }
            return parsed;
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("fact payload is invalid JSON", invalid);
        }
    }

    private static UUID requiredUuid(JsonNode payload, String name) {
        JsonNode node = payload.get(name);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            return UUID.fromString(node.textValue());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " is invalid", invalid);
        }
    }

    public record BatchResult(int claimed, int processed, int failed) {
        public BatchResult {
            if (claimed < 0 || processed < 0 || failed < 0 || processed + failed != claimed) {
                throw new IllegalArgumentException("invalid source absence batch counts");
            }
        }
    }
}
