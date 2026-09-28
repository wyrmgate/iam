package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.AccessIntentCommand;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemState;
import io.wyrmgate.iam.governance.application.AccessRequestModels.PrincipalConstraintKind;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.governance.application.AccessRequestModels.TargetKind;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Durable Governance consumer that applies authorized request intent through Access.
 *
 * <p>Access is invoked before the separate Governance APPLIED transaction. A
 * retry after either side commits is safe because AccessIntentCommand is
 * idempotent by RequestItem provenance.</p>
 */
public final class AccessRequestAccessApplicationService {

    private static final Duration CLAIM_LEASE =
            Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY =
            Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 50;
    private static final String INVALID_FACT =
            "request_item_authorized_fact_invalid";
    private static final String APPLICATION_FAILED =
            "request_item_access_application_failed";

    private final JdbcOutboxRepository outbox;
    private final AccessRequestRepository requests;
    private final AccessIntentCommand access;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public AccessRequestAccessApplicationService(
            JdbcOutboxRepository outbox,
            AccessRequestRepository requests,
            AccessIntentCommand access,
            TransactionExecutor transactions) {
        this(
                outbox,
                requests,
                access,
                transactions,
                Clock.systemUTC());
    }

    AccessRequestAccessApplicationService(
            JdbcOutboxRepository outbox,
            AccessRequestRepository requests,
            AccessIntentCommand access,
            TransactionExecutor transactions,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.access = Objects.requireNonNull(access, "access");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ProcessingBatchResult processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(AuthorizedAccessIntentSink.REQUEST_ITEM_AUTHORIZED),
                now,
                CLAIM_LEASE,
                BATCH_SIZE);

        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent claimedEvent : claimed) {
            try {
                apply(claimedEvent, clock.instant());
                outbox.markPublished(
                        claimedEvent.tenant(),
                        claimedEvent.event().eventId(),
                        clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        claimedEvent.tenant(),
                        claimedEvent.event().eventId(),
                        INVALID_FACT);
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        claimedEvent.tenant(),
                        claimedEvent.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        APPLICATION_FAILED);
                failed++;
            }
        }
        return new ProcessingBatchResult(
                claimed.size(), processed, failed);
    }

    private void apply(
            ClaimedOutboxEvent claimed,
            Instant now) {
        var event = claimed.event();
        if (event.eventVersion() != 1
                || !"request-item".equals(event.aggregateType())
                || event.aggregateId() == null) {
            throw new IllegalArgumentException(
                    "unsupported RequestItem authorization fact");
        }

        RequestItem item = requests.findItem(
                        claimed.tenant(),
                        event.aggregateId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "authorized RequestItem does not exist"));
        if (item.state() == ItemState.APPLIED) {
            return;
        }
        if (item.state() != ItemState.AUTHORIZED) {
            return;
        }

        var request = requests.findRequest(
                        claimed.tenant(),
                        item.accessRequestId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "AccessRequest does not exist"));

        AccessIntentCommand.Result application = access.applyRequestedAccess(
                claimed.tenant(),
                new AccessIntentCommand.RequestedAccess(
                        item.id(),
                        request.beneficiaryIdentityId(),
                        item.targetKind() == TargetKind.ROLE
                                ? AccessIntentCommand.TargetKind.ROLE
                                : AccessIntentCommand.TargetKind.ENTITLEMENT,
                        item.roleId(),
                        item.entitlementId(),
                        item.principalConstraintKind()
                                == PrincipalConstraintKind.ANY
                                ? AccessIntentCommand.PrincipalConstraintKind.ANY
                                : AccessIntentCommand.PrincipalConstraintKind.SPECIFIC,
                        item.specificPrincipalId(),
                        item.validFrom(),
                        item.validUntil()),
                now);

        transactions.required(() -> {
            RequestItem current = requests.findItem(
                            claimed.tenant(),
                            item.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "RequestItem disappeared during access application"));
            if (current.state() == ItemState.APPLIED) {
                if (!application.accessAssignmentId().equals(
                        current.accessAssignmentId())) {
                    throw new IllegalStateException(
                            "RequestItem is APPLIED to a different AccessAssignment");
                }
                return current;
            }
            if (current.state() != ItemState.AUTHORIZED) {
                return current;
            }
            return requests.updateItemState(
                    claimed.tenant(),
                    current.id(),
                    ItemState.APPLIED,
                    current.approvalCaseId(),
                    application.accessAssignmentId(),
                    current.evaluationCode(),
                    current.revision(),
                    now);
        });
    }

    public record ProcessingBatchResult(
            int claimed,
            int processed,
            int failed) {
        public ProcessingBatchResult {
            if (claimed < 0 || processed < 0 || failed < 0
                    || processed + failed > claimed) {
                throw new IllegalArgumentException(
                        "invalid processing batch counts");
            }
        }
    }
}
