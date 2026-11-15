package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.access.application.AccessAssignmentCommandException;
import io.wyrmgate.iam.access.application.AccessIntentCommand;
import io.wyrmgate.iam.access.application.AccessIntentApplicationQuery;
import io.wyrmgate.iam.governance.application.AccessRequestModels.EligibilityOutcome;
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

    private static final Set<String> TERMINAL_EXPIRED_CODES = Set.of(
            "validity_already_ended",
            "access_assignment_expired");

    private static final Set<String> TERMINAL_CANCELLED_CODES = Set.of(
            "identity_not_found",
            "identity_not_access_eligible",
            "authorization_identity_epoch_stale",
            "entitlement_not_found",
            "entitlement_retired",
            "application_target_retired",
            "entitlement_unavailable_terminal",
            "role_not_found",
            "role_unavailable_terminal",
            "specific_principal_required",
            "specific_principal_not_allowed",
            "principal_not_found",
            "principal_uncorrelated",
            "principal_identity_mismatch",
            "principal_target_mismatch",
            "specific_role_target_ambiguous",
            "invalid_validity_window",
            "request_item_access_intent_conflict");

    private final JdbcOutboxRepository outbox;
    private final AccessRequestRepository requests;
    private final AccessIntentCommand access;
    private final AccessIntentApplicationQuery replay;
    private final AccessRequestEligibilityEvaluator evaluator;
    private final RequestAdmissionDecisionService decisions;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public AccessRequestAccessApplicationService(
            JdbcOutboxRepository outbox,
            AccessRequestRepository requests,
            AccessIntentCommand access,
            AccessIntentApplicationQuery replay,
            AccessRequestEligibilityEvaluator evaluator,
            RequestAdmissionDecisionService decisions,
            TransactionExecutor transactions) {
        this(
                outbox,
                requests,
                access, replay, evaluator, decisions,
                transactions,
                Clock.systemUTC());
    }

    AccessRequestAccessApplicationService(
            JdbcOutboxRepository outbox,
            AccessRequestRepository requests,
            AccessIntentCommand access,
            AccessIntentApplicationQuery replay,
            AccessRequestEligibilityEvaluator evaluator,
            RequestAdmissionDecisionService decisions,
            TransactionExecutor transactions,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.access = Objects.requireNonNull(access, "access");
        this.replay = Objects.requireNonNull(replay, "replay");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
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

        var requested = new AccessIntentCommand.RequestedAccess(
                item.id(), request.beneficiaryIdentityId(),
                item.targetKind() == TargetKind.ROLE
                        ? AccessIntentCommand.TargetKind.ROLE : AccessIntentCommand.TargetKind.ENTITLEMENT,
                item.roleId(), item.entitlementId(),
                item.principalConstraintKind() == PrincipalConstraintKind.ANY
                        ? AccessIntentCommand.PrincipalConstraintKind.ANY : AccessIntentCommand.PrincipalConstraintKind.SPECIFIC,
                item.specificPrincipalId(), item.validFrom(), item.validUntil());

        // A historical Access commit must be recovered before any new evaluation.
        AccessIntentCommand.Result application = replay.findApplied(claimed.tenant(), requested).orElse(null);
        if (application == null) {
            if (item.validUntil() != null && !item.validUntil().isAfter(now)) {
                recordTerminalApplicationOutcome(claimed, item, ItemState.EXPIRED, "validity_already_ended", now);
                return;
            }
            var eligibility = RequestAdmissionDecisionService.evaluate(evaluator, claimed.tenant(), request, item);
            if (eligibility.outcome() == EligibilityOutcome.UNAVAILABLE) {
                throw new IllegalStateException("current mandatory admission evaluation unavailable");
            }
            var current = transactions.required(() -> {
                RequestItem latest = requests.findItem(claimed.tenant(), item.id()).orElseThrow();
                if (latest.state() != ItemState.AUTHORIZED || latest.revision() != item.revision()) return latest;
                return decisions.finish(claimed.tenant(), request, latest, eligibility, now);
            });
            // A changed material decision publishes a successor authorization fact, or
            // transitions to a terminal/pending-approval state. Do not apply the old revision.
            if (current.state() != ItemState.AUTHORIZED || current.revision() != item.revision()) return;
            var context = eligibility.context();
            try {
                application = access.applyRequestedAccess(claimed.tenant(), requested,
                        new AccessIntentCommand.Admission(context.identityEpoch(), context.targetFingerprint()), now);
            } catch (AccessAssignmentCommandException rejected) {
                ItemState terminal = terminalState(rejected.code());
                if (terminal == null) throw rejected;
                recordTerminalApplicationOutcome(claimed, item, terminal, rejected.code(), now);
                return;
            }
        }
        AccessIntentCommand.Result applied = application;

        transactions.required(() -> {
            RequestItem current = requests.findItem(
                            claimed.tenant(),
                            item.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "RequestItem disappeared during access application"));
            if (current.state() == ItemState.APPLIED) {
                if (!applied.accessAssignmentId().equals(
                        current.accessAssignmentId())) {
                    throw new IllegalStateException(
                            "RequestItem is APPLIED to a different AccessAssignment");
                }
                return current;
            }
            if (current.state() != ItemState.AUTHORIZED) {
                throw new IllegalStateException("committed Access result requires Governance reconciliation");
            }
            return requests.updateItemState(
                    claimed.tenant(),
                    current.id(),
                    ItemState.APPLIED,
                    current.approvalCaseId(),
                    applied.accessAssignmentId(),
                    current.evaluationCode(),
                    current.revision(),
                    now);
        });
    }

    private ItemState terminalState(String code) {
        if (TERMINAL_EXPIRED_CODES.contains(code)) {
            return ItemState.EXPIRED;
        }
        if (TERMINAL_CANCELLED_CODES.contains(code)) {
            return ItemState.CANCELLED;
        }
        return null;
    }

    private void recordTerminalApplicationOutcome(
            ClaimedOutboxEvent claimed,
            RequestItem item,
            ItemState terminalState,
            String code,
            Instant now) {
        transactions.required(() -> {
            RequestItem current = requests.findItem(
                            claimed.tenant(),
                            item.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "RequestItem disappeared during terminal access application"));
            if (current.state() != ItemState.AUTHORIZED || current.revision() != item.revision()) {
                return current;
            }
            return requests.updateItemState(
                    claimed.tenant(),
                    current.id(),
                    terminalState,
                    current.approvalCaseId(),
                    null,
                    code,
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
