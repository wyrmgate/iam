package io.wyrmgate.iam.access.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.AccessTargetReduction;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityFactSink;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityCauseKind;
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
import java.util.UUID;

/** Consumes Catalog availability triggers and creates Access-owned terminal-target reductions. */
public final class AccessTargetReductionIntakeService {

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 50;

    private final JdbcOutboxRepository outbox;
    private final AccessTargetReductionRepository reductions;
    private final AccessTargetReductionWorkSink work;
    private final CatalogAccessTargetAvailabilityQuery catalog;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public AccessTargetReductionIntakeService(
            JdbcOutboxRepository outbox,
            AccessTargetReductionRepository reductions,
            AccessTargetReductionWorkSink work,
            CatalogAccessTargetAvailabilityQuery catalog,
            IdGenerator ids,
            TransactionExecutor transactions,
            ObjectMapper json) {
        this(
                outbox,
                reductions,
                work,
                catalog,
                ids,
                transactions,
                json,
                Clock.systemUTC());
    }

    AccessTargetReductionIntakeService(
            JdbcOutboxRepository outbox,
            AccessTargetReductionRepository reductions,
            AccessTargetReductionWorkSink work,
            CatalogAccessTargetAvailabilityQuery catalog,
            IdGenerator ids,
            TransactionExecutor transactions,
            ObjectMapper json,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.reductions = Objects.requireNonNull(reductions, "reductions");
        this.work = Objects.requireNonNull(work, "work");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BatchResult processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(CatalogAccessTargetAvailabilityFactSink
                        .ACCESS_TARGET_AVAILABILITY_CHANGED),
                now,
                CLAIM_LEASE,
                BATCH_SIZE);
        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                consume(item, clock.instant());
                outbox.markPublished(
                        item.tenant(), item.event().eventId(), clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(),
                        item.event().eventId(),
                        "catalog_target_reduction_fact_invalid");
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "catalog_target_reduction_intake_failed");
                failed++;
            }
        }
        return new BatchResult(claimed.size(), processed, failed);
    }

    private void consume(ClaimedOutboxEvent item, Instant now) {
        var event = item.event();
        if (event.eventVersion() != 1) {
            throw new IllegalArgumentException(
                    "unsupported Catalog target availability fact");
        }
        JsonNode payload = payload(event.payloadJson());
        CatalogAccessTargetKind targetKind = enumField(
                payload, "targetKind", CatalogAccessTargetKind.class);
        UUID targetId = uuidField(payload, "targetId");
        CatalogAvailabilityCauseKind causeKind = enumField(
                payload, "causeKind", CatalogAvailabilityCauseKind.class);
        UUID causeId = uuidField(payload, "causeId");
        long causeRevision = longField(payload, "causeRevision");

        var current = catalog.currentAvailability(
                item.tenant(), targetKind, targetId).orElse(null);
        if (current == null
                || !current.terminal()
                || current.causeKind() != causeKind
                || !current.causeId().equals(causeId)
                || current.causeRevision() != causeRevision) {
            return;
        }

        AccessTargetReduction candidate = new AccessTargetReduction(
                ids.nextId(),
                AccessAssignment.TargetKind.valueOf(targetKind.name()),
                targetId,
                AccessTargetReduction.CauseKind.valueOf(causeKind.name()),
                causeId,
                causeRevision,
                AccessTargetReduction.State.RUNNING,
                event.occurredAt(),
                null,
                null,
                0,
                1,
                now,
                now,
                null);

        transactions.required(() -> {
            AccessTargetReduction reduction =
                    reductions.startIfAbsent(item.tenant(), candidate);
            if (reduction.id().equals(candidate.id())) {
                work.requested(
                        item.tenant(),
                        reduction,
                        event.correlationId(),
                        event.eventId());
            }
            return null;
        });
    }

    private JsonNode payload(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException(
                    "Catalog target availability payload is invalid JSON", invalid);
        }
    }

    private static UUID uuidField(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return UUID.fromString(value.textValue());
    }

    private static long longField(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || !value.canConvertToLong() || value.longValue() < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value.longValue();
    }

    private static <E extends Enum<E>> E enumField(
            JsonNode payload, String name, Class<E> type) {
        JsonNode value = payload.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            return Enum.valueOf(type, value.textValue());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(name + " is unsupported", invalid);
        }
    }

    public record BatchResult(int claimed, int processed, int failed) {
        public BatchResult {
            if (claimed < 0 || processed < 0 || failed < 0
                    || processed + failed != claimed) {
                throw new IllegalArgumentException("invalid target reduction intake batch counts");
            }
        }
    }
}
