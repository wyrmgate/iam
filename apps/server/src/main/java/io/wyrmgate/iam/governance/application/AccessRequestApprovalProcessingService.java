package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ApprovalPlan;
import io.wyrmgate.iam.governance.domain.ApprovalSubject;
import io.wyrmgate.iam.governance.domain.RequestItem;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AccessRequestApprovalProcessingService {

    private static final Duration CLAIM_LEASE =
            Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY =
            Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 100;

    private final JdbcOutboxRepository outbox;
    private final ApprovalRepository approvals;
    private final AccessRequestRepository requests;
    private final AccessRequestService requestService;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public AccessRequestApprovalProcessingService(
            JdbcOutboxRepository outbox,
            ApprovalRepository approvals,
            AccessRequestRepository requests,
            AccessRequestService requestService,
            TransactionExecutor transactions) {
        this(
                outbox,
                approvals,
                requests,
                requestService,
                transactions,
                Clock.systemUTC());
    }

    AccessRequestApprovalProcessingService(
            JdbcOutboxRepository outbox,
            ApprovalRepository approvals,
            AccessRequestRepository requests,
            AccessRequestService requestService,
            TransactionExecutor transactions,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.approvals = Objects.requireNonNull(
                approvals, "approvals");
        this.requests = Objects.requireNonNull(
                requests, "requests");
        this.requestService = Objects.requireNonNull(
                requestService, "requestService");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed =
                outbox.claimPending(
                        Set.of(ApprovalOutcomeSink.eventType(
                                ApprovalSubject.Kind.ACCESS_REQUEST_ITEM)),
                        now,
                        CLAIM_LEASE,
                        BATCH_SIZE);
        int processed = 0;
        for (ClaimedOutboxEvent claimedEvent : claimed) {
            try {
                process(claimedEvent, clock.instant());
                outbox.markPublished(
                        claimedEvent.tenant(),
                        claimedEvent.event().eventId(),
                        clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        claimedEvent.tenant(),
                        claimedEvent.event().eventId(),
                        "approval_outcome_invalid");
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        claimedEvent.tenant(),
                        claimedEvent.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        "access_request_approval_processing_failed");
            }
        }
        return processed;
    }

    private void process(
            ClaimedOutboxEvent claimed,
            Instant now) {
        var event = claimed.event();
        if (event.eventVersion() != 1
                || !"approval-plan".equals(
                        event.aggregateType())
                || event.aggregateId() == null) {
            throw new IllegalArgumentException(
                    "invalid approval outcome fact");
        }
        ApprovalPlan plan = approvals.findPlan(
                        claimed.tenant(),
                        event.aggregateId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "approval plan does not exist"));
        if (plan.subject().kind()
                != ApprovalSubject.Kind.ACCESS_REQUEST_ITEM) {
            throw new IllegalArgumentException(
                    "approval subject kind mismatch");
        }

        RequestItem item = requests.findItem(
                        claimed.tenant(),
                        plan.subject().id())
                .orElseThrow(() -> new IllegalArgumentException(
                        "request item does not exist"));
        if (item.approvalPlanId() != null
                && !item.approvalPlanId().equals(plan.id())) {
            if (plan.lifecycleState()
                    == ApprovalPlan.LifecycleState.SUPERSEDED) {
                return;
            }
            throw new IllegalArgumentException(
                    "request item approval plan mismatch");
        }

        switch (plan.lifecycleState()) {
            case APPROVED -> processApproved(
                    claimed, plan, item, now);
            case REJECTED -> processTerminal(
                    claimed,
                    item,
                    RequestItem.LifecycleState.REJECTED,
                    now);
            case EXPIRED -> processTerminal(
                    claimed,
                    item,
                    RequestItem.LifecycleState.EXPIRED,
                    now);
            case SUPERSEDED -> processSuperseded(
                    claimed, item, now);
            case PENDING -> throw new IllegalArgumentException(
                    "pending plan emitted terminal outcome fact");
        }
    }

    private void processApproved(
            ClaimedOutboxEvent claimed,
            ApprovalPlan plan,
            RequestItem item,
            Instant now) {
        RequestItem current = requestService.finalizeApprovedItem(
                claimed.tenant(),
                item.id(),
                plan.id(),
                now);
        if (current.lifecycleState()
                == RequestItem.LifecycleState.PENDING_APPROVAL
                || current.lifecycleState()
                == RequestItem.LifecycleState.APPLIED
                || current.lifecycleState()
                == RequestItem.LifecycleState.DENIED) {
            return;
        }
        throw new IllegalStateException(
                "approved RequestItem ended in an incompatible state");
    }

    private void processTerminal(
            ClaimedOutboxEvent claimed,
            RequestItem item,
            RequestItem.LifecycleState terminal,
            Instant now) {
        if (item.lifecycleState() == terminal) {
            return;
        }
        if (item.lifecycleState()
                != RequestItem.LifecycleState.PENDING_APPROVAL) {
            throw new IllegalStateException(
                    "terminal approval outcome cannot change current RequestItem state");
        }
        RequestItem updated = transactions.required(() ->
                requests.updateItemState(
                        claimed.tenant(),
                        item.id(),
                        terminal,
                        item.approvalPlanId(),
                        null,
                        null,
                        item.revision(),
                        now));
        requestService.refreshRequestCompletion(
                claimed.tenant(),
                updated.accessRequestId(),
                now);
    }

    private void processSuperseded(
            ClaimedOutboxEvent claimed,
            RequestItem item,
            Instant now) {
        if (item.lifecycleState()
                != RequestItem.LifecycleState.PENDING_APPROVAL) {
            return;
        }
        RequestItem evaluating = transactions.required(() ->
                requests.updateItemState(
                        claimed.tenant(),
                        item.id(),
                        RequestItem.LifecycleState.EVALUATING,
                        null,
                        null,
                        null,
                        item.revision(),
                        now));
        requestService.reevaluate(
                claimed.tenant(),
                evaluating.id(),
                now);
    }
}
