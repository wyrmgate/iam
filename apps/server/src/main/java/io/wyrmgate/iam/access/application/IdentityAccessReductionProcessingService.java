package io.wyrmgate.iam.access.application;

import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.IdentityAccessReduction;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class IdentityAccessReductionProcessingService {

    private static final Duration CLAIM_LEASE =
            Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY =
            Duration.ofSeconds(5);
    private static final int CLAIM_BATCH = 20;
    private static final int PAGE_SIZE = 200;

    private final JdbcOutboxRepository outbox;
    private final IdentityAccessReductionRepository reductions;
    private final AccessAssignmentRepository assignments;
    private final AccessAssignmentCommandService commands;
    private final IdentityAccessReferenceQuery identities;
    private final IdentityAccessReductionWorkSink work;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public IdentityAccessReductionProcessingService(
            JdbcOutboxRepository outbox,
            IdentityAccessReductionRepository reductions,
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands,
            IdentityAccessReferenceQuery identities,
            IdentityAccessReductionWorkSink work,
            TransactionExecutor transactions) {
        this(
                outbox,
                reductions,
                assignments,
                commands,
                identities,
                work,
                transactions,
                Clock.systemUTC());
    }

    IdentityAccessReductionProcessingService(
            JdbcOutboxRepository outbox,
            IdentityAccessReductionRepository reductions,
            AccessAssignmentRepository assignments,
            AccessAssignmentCommandService commands,
            IdentityAccessReferenceQuery identities,
            IdentityAccessReductionWorkSink work,
            TransactionExecutor transactions,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.reductions = Objects.requireNonNull(
                reductions, "reductions");
        this.assignments = Objects.requireNonNull(
                assignments, "assignments");
        this.commands = Objects.requireNonNull(
                commands, "commands");
        this.identities = Objects.requireNonNull(
                identities, "identities");
        this.work = Objects.requireNonNull(work, "work");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BatchResult processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed =
                outbox.claimPending(
                        Set.of(IdentityAccessReductionWorkSink
                                .REDUCTION_REQUESTED),
                        now,
                        CLAIM_LEASE,
                        CLAIM_BATCH);
        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                processOne(item, clock.instant());
                outbox.markPublished(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(),
                        item.event().eventId(),
                        "identity_access_reduction_work_invalid");
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "identity_access_reduction_failed");
                failed++;
            }
        }
        return new BatchResult(
                claimed.size(), processed, failed);
    }

    private void processOne(
            ClaimedOutboxEvent item,
            Instant now) {
        var event = item.event();
        if (event.eventVersion() != 1
                || !"identity-access-reduction".equals(
                        event.aggregateType())
                || event.aggregateId() == null
                || event.aggregateRevision() == null) {
            throw new IllegalArgumentException(
                    "unsupported Identity access reduction work");
        }

        IdentityAccessReduction reduction =
                reductions.findById(
                                item.tenant(),
                                event.aggregateId())
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "IdentityAccessReduction does not exist"));

        if (reduction.state()
                == IdentityAccessReduction.State.COMPLETED) {
            return;
        }
        if (event.aggregateRevision()
                < reduction.revision()) {
            return;
        }
        if (event.aggregateRevision()
                > reduction.revision()) {
            throw new IllegalStateException(
                    "IdentityAccessReduction work is ahead of authoritative process state");
        }

        var status = identities.accessStatus(
                item.tenant(),
                reduction.identityId());
        if (status.status()
                == IdentityAccessReferenceQuery
                        .AccessStatus.ACCESS_ELIGIBLE) {
            completeWithoutFurtherReduction(
                    item, reduction, now);
            return;
        }
        if (status.status()
                        == IdentityAccessReferenceQuery
                                .AccessStatus.ACCESS_INELIGIBLE
                && status.revision()
                        < reduction.sourceIdentityRevision()) {
            throw new IllegalStateException(
                    "Identity access status is older than the reduction source revision");
        }

        List<AccessAssignment> page =
                assignments.findIdentityReductionPage(
                        item.tenant(),
                        reduction.identityId(),
                        reduction.snapshotAt(),
                        reduction.afterCreatedAt(),
                        reduction.afterAssignmentId(),
                        PAGE_SIZE + 1);
        boolean hasMore = page.size() > PAGE_SIZE;
        List<AccessAssignment> selected =
                hasMore
                        ? List.copyOf(
                                page.subList(0, PAGE_SIZE))
                        : List.copyOf(page);

        transactions.required(() -> {
            IdentityAccessReduction current =
                    reductions.findById(
                                    item.tenant(),
                                    reduction.id())
                            .orElseThrow();
            if (current.state()
                    == IdentityAccessReduction.State.COMPLETED) {
                return null;
            }
            if (current.revision()
                    != reduction.revision()
                    || !Objects.equals(
                            current.afterCreatedAt(),
                            reduction.afterCreatedAt())
                    || !Objects.equals(
                            current.afterAssignmentId(),
                            reduction.afterAssignmentId())) {
                return null;
            }

            var currentStatus =
                    identities.accessStatus(
                            item.tenant(),
                            current.identityId());
            if (currentStatus.status()
                    == IdentityAccessReferenceQuery
                            .AccessStatus.ACCESS_ELIGIBLE) {
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
            if (currentStatus.status()
                            == IdentityAccessReferenceQuery
                                    .AccessStatus.ACCESS_INELIGIBLE
                    && currentStatus.revision()
                            < current.sourceIdentityRevision()) {
                throw new IllegalStateException(
                        "Identity access status is older than the reduction source revision");
            }

            int terminated = 0;
            for (AccessAssignment snapshot : selected) {
                AccessAssignment latest =
                        assignments.findById(
                                        item.tenant(),
                                        snapshot.id())
                                .orElse(null);
                if (latest == null
                        || isTerminal(
                                latest.lifecycleState())) {
                    continue;
                }
                commands.terminate(
                        item.tenant(),
                        latest.id(),
                        latest.revision(),
                        now);
                terminated++;
            }

            Instant afterCreatedAt =
                    selected.isEmpty()
                            ? current.afterCreatedAt()
                            : selected.getLast().createdAt();
            java.util.UUID afterAssignmentId =
                    selected.isEmpty()
                            ? current.afterAssignmentId()
                            : selected.getLast().id();
            IdentityAccessReduction updated =
                    reductions.recordPage(
                            item.tenant(),
                            current.id(),
                            afterCreatedAt,
                            afterAssignmentId,
                            terminated,
                            !hasMore,
                            current.revision(),
                            now);
            if (hasMore) {
                work.reductionRequested(
                        item.tenant(),
                        updated,
                        event.correlationId(),
                        event.eventId());
            }
            return null;
        });
    }

    private void completeWithoutFurtherReduction(
            ClaimedOutboxEvent item,
            IdentityAccessReduction reduction,
            Instant now) {
        transactions.required(() -> {
            IdentityAccessReduction current =
                    reductions.findById(
                                    item.tenant(),
                                    reduction.id())
                            .orElseThrow();
            if (current.state()
                    == IdentityAccessReduction.State.RUNNING
                    && current.revision()
                            == reduction.revision()) {
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

    private static boolean isTerminal(
            AccessAssignment.LifecycleState state) {
        return state
                == AccessAssignment.LifecycleState.REVOKED
                || state
                == AccessAssignment.LifecycleState.EXPIRED
                || state
                == AccessAssignment.LifecycleState.CANCELLED;
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
                        "invalid Identity reduction processing batch counts");
            }
        }
    }
}
