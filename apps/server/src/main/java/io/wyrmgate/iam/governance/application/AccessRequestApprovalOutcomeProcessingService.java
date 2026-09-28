package io.wyrmgate.iam.governance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.platform.persistence.ClaimedOutboxEvent;
import io.wyrmgate.iam.platform.persistence.JdbcOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class AccessRequestApprovalOutcomeProcessingService {

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 50;
    private static final String INVALID_FACT =
            "approval_request_item_outcome_invalid";
    private static final String APPLY_FAILED =
            "approval_request_item_apply_failed";

    private final JdbcOutboxRepository outbox;
    private final AccessRequestService requests;
    private final ObjectMapper json;
    private final Clock clock;

    public AccessRequestApprovalOutcomeProcessingService(
            JdbcOutboxRepository outbox,
            AccessRequestService requests,
            ObjectMapper json) {
        this(outbox, requests, json, Clock.systemUTC());
    }

    AccessRequestApprovalOutcomeProcessingService(
            JdbcOutboxRepository outbox,
            AccessRequestService requests,
            ObjectMapper json,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ProcessingBatchResult processAvailable() {
        Instant now = clock.instant();
        List<ClaimedOutboxEvent> claimed = outbox.claimPending(
                Set.of(ApprovalOutcomeFactSink.REQUEST_ITEM_OUTCOME),
                now,
                CLAIM_LEASE,
                BATCH_SIZE);
        int processed = 0;
        int failed = 0;
        for (ClaimedOutboxEvent item : claimed) {
            try {
                Outcome outcome = parse(item);
                requests.applyApprovalOutcome(
                        item.tenant(),
                        outcome.approvalCaseId(),
                        outcome.requestItemId(),
                        outcome.subjectRevision(),
                        outcome.outcome(),
                        clock.instant());
                outbox.markPublished(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant());
                processed++;
            } catch (IllegalArgumentException invalid) {
                outbox.markTerminalFailure(
                        item.tenant(),
                        item.event().eventId(),
                        INVALID_FACT);
                failed++;
            } catch (RuntimeException retryable) {
                outbox.markFailed(
                        item.tenant(),
                        item.event().eventId(),
                        clock.instant().plus(RETRY_DELAY),
                        APPLY_FAILED);
                failed++;
            }
        }
        return new ProcessingBatchResult(
                claimed.size(), processed, failed);
    }

    private Outcome parse(ClaimedOutboxEvent item) {
        if (item.event().eventVersion() != 1
                || !"governance-approval-case".equals(
                        item.event().aggregateType())) {
            throw new IllegalArgumentException(
                    "unsupported request-item approval outcome fact");
        }
        try {
            JsonNode root = json.readTree(
                    item.event().payloadJson());
            if (!"REQUEST_ITEM".equals(text(root, "subjectType"))) {
                throw new IllegalArgumentException(
                        "approval outcome subject is not REQUEST_ITEM");
            }
            ApprovalCase.LifecycleState outcome =
                    ApprovalCase.LifecycleState.valueOf(
                            text(root, "outcome"));
            if (outcome != ApprovalCase.LifecycleState.APPROVED
                    && outcome
                            != ApprovalCase.LifecycleState.REJECTED) {
                throw new IllegalArgumentException(
                        "approval outcome is not terminal decision");
            }
            return new Outcome(
                    UUID.fromString(text(root, "approvalCaseId")),
                    UUID.fromString(text(root, "subjectId")),
                    longValue(root, "subjectRevision"),
                    outcome);
        } catch (JsonProcessingException
                | IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "request-item approval outcome payload is invalid",
                    invalid);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                    "missing text field " + field);
        }
        return value.textValue();
    }

    private static long longValue(
            JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalArgumentException(
                    "missing long field " + field);
        }
        long result = value.longValue();
        if (result < 1) {
            throw new IllegalArgumentException(
                    field + " must be positive");
        }
        return result;
    }

    private record Outcome(
            UUID approvalCaseId,
            UUID requestItemId,
            long subjectRevision,
            ApprovalCase.LifecycleState outcome) {}

    public record ProcessingBatchResult(
            int claimed, int processed, int failed) {
        public ProcessingBatchResult {
            if (claimed < 0
                    || processed < 0
                    || failed < 0
                    || processed + failed != claimed) {
                throw new IllegalArgumentException(
                        "invalid approval outcome processing counts");
            }
        }
    }
}
