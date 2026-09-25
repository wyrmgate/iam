package io.wyrmgate.iam.integration.application;

import io.wyrmgate.iam.access.application.DesiredPrincipalFactSink;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PrincipalProvisioningPlanningProcessor {

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(10);
    private static final int BATCH_SIZE = 100;

    private final JdbcOutboxRepository outbox;
    private final PrincipalProvisioningPlannerService planner;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public PrincipalProvisioningPlanningProcessor(
            JdbcOutboxRepository outbox,
            PrincipalProvisioningPlannerService planner,
            TransactionExecutor transactions) {
        this(outbox, planner, transactions, Clock.systemUTC());
    }

    PrincipalProvisioningPlanningProcessor(
            JdbcOutboxRepository outbox,
            PrincipalProvisioningPlannerService planner,
            TransactionExecutor transactions,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(
                        DesiredPrincipalFactSink.DESIRED_PRINCIPAL_CHANGED,
                        DesiredPrincipalFactSink.DESIRED_PRINCIPAL_REVALIDATE),
                now,
                CLAIM_LEASE,
                BATCH_SIZE);
        int processed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                var event = item.event();
                if (event.eventVersion() != 1
                        || !"desired-principal".equals(event.aggregateType())
                        || event.aggregateId() == null
                        || event.aggregateRevision() == null) {
                    outbox.markTerminalFailure(
                            item.tenant(),
                            event.eventId(),
                            "desired_principal_fact_invalid");
                    continue;
                }
                var result = transactions.required(() -> planner.plan(
                        item.tenant(),
                        event.aggregateId(),
                        event.aggregateRevision(),
                        event.correlationId(),
                        event.causationId(),
                        clock.instant()));
                if (result == PrincipalProvisioningPlannerService.PlanResult.RETRY) {
                    outbox.markFailed(
                            item.tenant(),
                            event.eventId(),
                            clock.instant().plus(RETRY_DELAY),
                            "principal_provisioning_planning_unavailable");
                } else {
                    outbox.markPublished(
                            item.tenant(), event.eventId(), clock.instant());
                    processed++;
                }
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "principal_provisioning_planning_failed");
            }
        }
        return processed;
    }
}
