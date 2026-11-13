package io.wyrmgate.iam.access.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.catalog.application.RoleExpansionFactSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded Access-owned fan-out from a Catalog Role expansion fact to current
 * assignment projection repair work. No authoritative assignment is mutated here.
 *
 * <p>Each page atomically acknowledges its input, emits independent assignment
 * work and, when necessary, commits the next typed continuation in the existing
 * transactional outbox. A failed transaction cannot lose or partially advance
 * a page. Assignment workers always re-read current authority.</p>
 */
public final class RoleExpansionProjectionRefreshService {

    public static final String PAGE_REQUESTED = "access.role-projection-refresh-page-requested";
    public static final String ASSIGNMENT_REFRESH_REQUESTED =
            "access.assignment-projection-refresh-requested";

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int CLAIM_BATCH = 20;
    private static final int PAGE_SIZE = 100;

    private final JdbcOutboxRepository outbox;
    private final AccessAssignmentRepository assignments;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public RoleExpansionProjectionRefreshService(
            JdbcOutboxRepository outbox, AccessAssignmentRepository assignments,
            IdGenerator ids, TransactionExecutor transactions, ObjectMapper json) {
        this(outbox, assignments, ids, transactions, json, Clock.systemUTC());
    }

    RoleExpansionProjectionRefreshService(
            JdbcOutboxRepository outbox, AccessAssignmentRepository assignments,
            IdGenerator ids, TransactionExecutor transactions, ObjectMapper json, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BatchResult processAvailable() {
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(RoleExpansionFactSink.ROLE_EXPANSION_CHANGED, PAGE_REQUESTED),
                clock.instant(), CLAIM_LEASE, CLAIM_BATCH);
        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                processOne(item, clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(), item.event().eventId(), "role_projection_refresh_invalid");
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(item.tenant(), item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY), "role_projection_refresh_failed");
                failed++;
            }
        }
        return new BatchResult(claimed.size(), processed, failed);
    }

    private void processOne(ClaimedOutboxEvent item, Instant now) {
        OutboxEvent event = item.event();
        if (event.eventVersion() != 1
                || !"role".equals(event.aggregateType())
                || event.aggregateId() == null
                || event.aggregateRevision() == null) {
            throw new IllegalArgumentException("invalid Role projection refresh input");
        }
        Cursor cursor = RoleExpansionFactSink.ROLE_EXPANSION_CHANGED.equals(event.eventType())
                ? new Cursor(now, null, null)
                : readCursor(event.payloadJson());
        UUID correlation = event.correlationId() == null
                ? event.eventId() : event.correlationId();

        transactions.required(() -> {
            // The conditional acknowledgement also fences duplicate claimants.
            // It commits only with all work emitted by this page.
            if (!outbox.markPublished(item.tenant(), event.eventId(), now)) {
                return null;
            }
            List<AccessAssignment> page = assignments.findRoleRefreshPage(
                    item.tenant(), event.aggregateId(), cursor.snapshotAt(),
                    cursor.afterCreatedAt(), cursor.afterAssignmentId(), PAGE_SIZE + 1);
            boolean more = page.size() > PAGE_SIZE;
            List<AccessAssignment> selected = more ? page.subList(0, PAGE_SIZE) : page;
            for (AccessAssignment assignment : selected) {
                outbox.append(item.tenant(), new OutboxEvent(
                        ids.nextId(), ASSIGNMENT_REFRESH_REQUESTED, 1,
                        "access-assignment", assignment.id(), assignment.revision(),
                        now, correlation, event.eventId(), "{}"), now);
            }
            if (more) {
                AccessAssignment last = selected.getLast();
                String payload = json.createObjectNode()
                        .put("snapshotAt", cursor.snapshotAt().toString())
                        .put("afterCreatedAt", last.createdAt().toString())
                        .put("afterAssignmentId", last.id().toString())
                        .toString();
                outbox.append(item.tenant(), new OutboxEvent(
                        ids.nextId(), PAGE_REQUESTED, 1, "role",
                        event.aggregateId(), event.aggregateRevision(), now,
                        correlation, event.eventId(), payload), now);
            }
            return null;
        });
    }

    private Cursor readCursor(String payload) {
        try {
            JsonNode node = json.readTree(payload);
            Instant snapshotAt = Instant.parse(node.path("snapshotAt").asText());
            Instant afterCreatedAt = Instant.parse(node.path("afterCreatedAt").asText());
            UUID afterId = UUID.fromString(node.path("afterAssignmentId").asText());
            if (afterCreatedAt.isAfter(snapshotAt)) {
                throw new IllegalArgumentException("Role refresh checkpoint exceeds snapshot");
            }
            return new Cursor(snapshotAt, afterCreatedAt, afterId);
        } catch (JsonProcessingException | java.time.DateTimeException invalid) {
            throw new IllegalArgumentException("invalid Role refresh continuation", invalid);
        }
    }

    private record Cursor(Instant snapshotAt, Instant afterCreatedAt, UUID afterAssignmentId) {}

    public record BatchResult(int claimed, int processed, int failed) {
        public BatchResult {
            if (claimed < 0 || processed < 0 || failed < 0 || processed + failed != claimed) {
                throw new IllegalArgumentException("invalid Role refresh batch counts");
            }
        }
    }
}
