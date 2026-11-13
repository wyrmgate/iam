package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.AccessTargetReduction;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetAvailabilityQuery;
import io.wyrmgate.iam.catalog.domain.CatalogAccessTargetKind;
import io.wyrmgate.iam.catalog.domain.CatalogAvailabilityCauseKind;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded Access-owned repair for assignments whose Catalog target remains terminal. */
public final class AccessTargetReductionProcessingService {

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int CLAIM_BATCH = 20;
    private static final int PAGE_SIZE = 200;

    private final JdbcOutboxRepository outbox;
    private final AccessTargetReductionRepository reductions;
    private final AccessAssignmentRepository assignments;
    private final AccessAssignmentCommandService commands;
    private final CatalogAccessTargetAvailabilityQuery catalog;
    private final AccessTargetReductionWorkSink work;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public AccessTargetReductionProcessingService(
            JdbcOutboxRepository outbox,
            AccessTargetReductionRepository reductions,
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands,
            CatalogAccessTargetAvailabilityQuery catalog,
            AccessTargetReductionWorkSink work,
            TransactionExecutor transactions) {
        this(
                outbox,
                reductions,
                assignments,
                commands,
                catalog,
                work,
                transactions,
                Clock.systemUTC());
    }

    public AccessTargetReductionProcessingService(
            JdbcOutboxRepository outbox,
            AccessTargetReductionRepository reductions,
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands,
            CatalogAccessTargetAvailabilityQuery catalog,
            AccessTargetReductionWorkSink work,
            TransactionExecutor transactions,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.reductions = Objects.requireNonNull(reductions, "reductions");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.work = Objects.requireNonNull(work, "work");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BatchResult processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(AccessTargetReductionWorkSink.REDUCTION_REQUESTED),
                now,
                CLAIM_LEASE,
                CLAIM_BATCH);
        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                processOne(item, clock.instant());
                outbox.markPublished(
                        item.tenant(), item.event().eventId(), clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(),
                        item.event().eventId(),
                        "catalog_target_reduction_work_invalid");
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "catalog_target_reduction_failed");
                failed++;
            }
        }
        return new BatchResult(claimed.size(), processed, failed);
    }

    private void processOne(ClaimedOutboxEvent item, Instant now) {
        var event = item.event();
        if (event.eventVersion() != 1
                || !"access-target-reduction".equals(event.aggregateType())
                || event.aggregateId() == null
                || event.aggregateRevision() == null) {
            throw new IllegalArgumentException("unsupported AccessTargetReduction work");
        }

        AccessTargetReduction reduction = reductions.findById(
                        item.tenant(), event.aggregateId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "AccessTargetReduction does not exist"));
        if (reduction.state() == AccessTargetReduction.State.COMPLETED
                || event.aggregateRevision() < reduction.revision()) {
            return;
        }
        if (event.aggregateRevision() > reduction.revision()) {
            throw new IllegalStateException(
                    "AccessTargetReduction work is ahead of authoritative process state");
        }
        if (!sameTerminalCause(item, reduction)) {
            complete(item, reduction, now);
            return;
        }

        List<AccessAssignment> page = assignments.findTargetReductionPage(
                item.tenant(),
                reduction.targetKind(),
                reduction.targetId(),
                reduction.afterCreatedAt(),
                reduction.afterAssignmentId(),
                PAGE_SIZE + 1);
        boolean hasMore = page.size() > PAGE_SIZE;
        List<AccessAssignment> selected =
                hasMore ? List.copyOf(page.subList(0, PAGE_SIZE)) : List.copyOf(page);

        transactions.required(() -> {
            AccessTargetReduction current = reductions.findById(
                            item.tenant(), reduction.id())
                    .orElseThrow();
            if (current.state() == AccessTargetReduction.State.COMPLETED
                    || current.revision() != reduction.revision()
                    || !Objects.equals(current.afterCreatedAt(), reduction.afterCreatedAt())
                    || !Objects.equals(current.afterAssignmentId(), reduction.afterAssignmentId())) {
                return null;
            }
            if (!sameTerminalCause(item, current)) {
                reductions.recordPage(
                        item.tenant(),
                        current.id(),
                        current.afterCreatedAt(),
                        current.afterAssignmentId(),
                        0,
                        true,
                        current.revision(),
                        now);
                return null;
            }

            for (AccessAssignment snapshot : selected) {
                AccessAssignment latest = assignments.findById(
                                item.tenant(), snapshot.id())
                        .orElse(null);
                if (latest == null || isTerminal(latest.lifecycleState())) {
                    continue;
                }
                commands.terminate(
                        item.tenant(),
                        latest.id(),
                        latest.revision(),
                        now);
            }

            Instant afterCreatedAt = selected.isEmpty()
                    ? current.afterCreatedAt()
                    : selected.getLast().createdAt();
            java.util.UUID afterAssignmentId = selected.isEmpty()
                    ? current.afterAssignmentId()
                    : selected.getLast().id();
            AccessTargetReduction updated = reductions.recordPage(
                    item.tenant(),
                    current.id(),
                    afterCreatedAt,
                    afterAssignmentId,
                    selected.size(),
                    !hasMore,
                    current.revision(),
                    now);
            if (hasMore) {
                work.requested(
                        item.tenant(),
                        updated,
                        event.correlationId(),
                        event.eventId());
            }
            return null;
        });
    }

    private boolean sameTerminalCause(
            ClaimedOutboxEvent item,
            AccessTargetReduction reduction) {
        var availability = catalog.currentAvailability(
                        item.tenant(),
                        CatalogAccessTargetKind.valueOf(reduction.targetKind().name()),
                        reduction.targetId())
                .orElse(null);
        return availability != null
                && availability.terminal()
                && availability.causeKind()
                        == CatalogAvailabilityCauseKind.valueOf(
                                reduction.sourceCauseKind().name())
                && availability.causeId().equals(reduction.sourceCauseId())
                && availability.causeRevision() == reduction.sourceCauseRevision();
    }

    private void complete(
            ClaimedOutboxEvent item,
            AccessTargetReduction reduction,
            Instant now) {
        transactions.required(() -> {
            AccessTargetReduction current = reductions.findById(
                            item.tenant(), reduction.id())
                    .orElseThrow();
            if (current.state() == AccessTargetReduction.State.RUNNING
                    && current.revision() == reduction.revision()) {
                reductions.recordPage(
                        item.tenant(),
                        current.id(),
                        current.afterCreatedAt(),
                        current.afterAssignmentId(),
                        0,
                        true,
                        current.revision(),
                        now);
            }
            return null;
        });
    }

    private static boolean isTerminal(AccessAssignment.LifecycleState state) {
        return state == AccessAssignment.LifecycleState.REVOKED
                || state == AccessAssignment.LifecycleState.EXPIRED
                || state == AccessAssignment.LifecycleState.CANCELLED;
    }

    public record BatchResult(int claimed, int processed, int failed) {
        public BatchResult {
            if (claimed < 0 || processed < 0 || failed < 0
                    || processed + failed != claimed) {
                throw new IllegalArgumentException("invalid target reduction processing batch counts");
            }
        }
    }
}
