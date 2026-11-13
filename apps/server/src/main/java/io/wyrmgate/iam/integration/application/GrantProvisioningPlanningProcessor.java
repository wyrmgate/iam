package io.wyrmgate.iam.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.access.application.DesiredGrantFactSink;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.OutboxEvent;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Integration-owned durable cleanup planning; one bounded route page per work item. */
public final class GrantProvisioningPlanningProcessor {
    static final String CLEANUP_PAGE_REQUESTED = "integration.grant-cleanup-page-requested";
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(10);
    private static final int BATCH_SIZE = 100;

    private final JdbcOutboxRepository outbox;
    private final GrantProvisioningPlannerService planner;
    private final TransactionExecutor transactions;
    private final IdGenerator ids;
    private final ObjectMapper json;
    private final Clock clock;

    public GrantProvisioningPlanningProcessor(
            JdbcOutboxRepository outbox, GrantProvisioningPlannerService planner,
            TransactionExecutor transactions, IdGenerator ids, ObjectMapper json) {
        this(outbox, planner, transactions, ids, json, Clock.systemUTC());
    }

    GrantProvisioningPlanningProcessor(
            JdbcOutboxRepository outbox, GrantProvisioningPlannerService planner,
            TransactionExecutor transactions, IdGenerator ids, ObjectMapper json, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int processAvailable() {
        var claimed = outbox.claimPending(
                Set.of(DesiredGrantFactSink.DESIRED_GRANT_CHANGED, CLEANUP_PAGE_REQUESTED),
                clock.instant(), CLAIM_LEASE, BATCH_SIZE);
        int processed = 0;
        for (var item : claimed) {
            var event = item.event();
            try {
                if (event.eventVersion() != 1 || !"desired-grant".equals(event.aggregateType())
                        || event.aggregateId() == null || event.aggregateRevision() == null) {
                    outbox.markTerminalFailure(item.tenant(), event.eventId(), "desired_grant_fact_invalid");
                    continue;
                }
                boolean continuation = CLEANUP_PAGE_REQUESTED.equals(event.eventType());
                var checkpoint = continuation ? readCheckpoint(event.payloadJson())
                        : new Checkpoint(null, false);
                boolean done = transactions.required(() -> {
                    Instant now = clock.instant();
                    if (!outbox.markPublished(item.tenant(), event.eventId(), now)) return false;
                    UUID correlation = event.correlationId() == null ? event.eventId() : event.correlationId();
                    var result = planner.planPage(item.tenant(), event.aggregateId(),
                            event.aggregateRevision(), correlation, event.eventId(), now,
                            checkpoint.after(), checkpoint.hadRemoval(), continuation);
                    if (result.outcome() == GrantProvisioningPlannerService.PlanResult.RETRY) {
                        throw new RetryUnavailable();
                    }
                    if (result.more()) {
                        UUID next = ids.nextId();
                        outbox.append(item.tenant(), new OutboxEvent(next, CLEANUP_PAGE_REQUESTED,
                                1, "desired-grant", event.aggregateId(), event.aggregateRevision(),
                                now, correlation, event.eventId(),
                                writeCheckpoint(new Checkpoint(result.after(), result.hadRemoval()))), now);
                        if (result.delayed()) {
                            outbox.markFailed(item.tenant(), next, now.plus(RETRY_DELAY),
                                    "grant_cleanup_waiting_for_safe_progress");
                        }
                    }
                    return true;
                });
                if (done) processed++;
            } catch (InvalidCheckpoint invalid) {
                outbox.markTerminalFailure(item.tenant(), event.eventId(), "grant_cleanup_checkpoint_invalid");
            } catch (RetryUnavailable unavailable) {
                outbox.markFailed(item.tenant(), event.eventId(), clock.instant().plus(RETRY_DELAY),
                        "grant_provisioning_planning_unavailable");
            } catch (RuntimeException retryable) {
                outbox.markFailed(item.tenant(), event.eventId(), clock.instant().plus(RETRY_DELAY),
                        "grant_provisioning_planning_failed");
            }
        }
        return processed;
    }

    private Checkpoint readCheckpoint(String payload) {
        try {
            var node = json.readTree(payload);
            if (!node.isObject() || !node.path("hadRemoval").isBoolean()) throw new InvalidCheckpoint();
            var cursor = node.get("after");
            if (cursor == null || cursor.isNull()) return new Checkpoint(null, node.get("hadRemoval").asBoolean());
            var binding = cursor.get("connectorBindingId");
            var entitlement = cursor.get("providerEntitlementId");
            var principal = cursor.get("providerPrincipalId");
            if (binding == null || !binding.isTextual() || entitlement == null || !entitlement.isTextual()
                    || principal == null || !principal.isTextual()) throw new InvalidCheckpoint();
            return new Checkpoint(new GrantProvisioningRepository.CleanupCursor(
                    UUID.fromString(binding.asText()), entitlement.asText(), principal.asText()),
                    node.get("hadRemoval").asBoolean());
        } catch (Exception invalid) {
            throw new InvalidCheckpoint();
        }
    }

    private String writeCheckpoint(Checkpoint checkpoint) {
        try {
            return json.writeValueAsString(checkpoint);
        } catch (Exception invalid) {
            throw new IllegalStateException("cleanup checkpoint could not be encoded", invalid);
        }
    }

    private record Checkpoint(GrantProvisioningRepository.CleanupCursor after, boolean hadRemoval) {}
    private static final class RetryUnavailable extends RuntimeException {}
    private static final class InvalidCheckpoint extends RuntimeException {}
}
