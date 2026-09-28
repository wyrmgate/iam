package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.api.governance.GovernanceApprovalApiModels.ApprovalCaseResource;
import io.wyrmgate.iam.api.governance.GovernanceApprovalApiModels.ApprovalInboxItemResource;
import io.wyrmgate.iam.api.governance.GovernanceApprovalApiModels.ApprovalInboxPage;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.governance.application.ApprovalQueryService.InboxPosition;
import io.wyrmgate.iam.governance.application.ApprovalRepository;
import io.wyrmgate.iam.governance.domain.ApprovalCase;
import io.wyrmgate.iam.governance.domain.ApprovalDecision;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GovernanceApprovalController {

    private static final int DEFAULT_LIMIT = 50;

    private final ApprovalQueryService queries;
    private final GovernanceApprovalApiMutationService mutations;
    private final IdGenerator ids;
    private final GovernanceApprovalCursorCodec cursors;

    public GovernanceApprovalController(
            ApprovalQueryService queries,
            GovernanceApprovalApiMutationService mutations,
            IdGenerator ids,
            GovernanceApprovalCursorCodec cursors) {
        this.queries = queries;
        this.mutations = mutations;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping("/approval-inbox")
    public ResponseEntity<ApprovalInboxPage> inbox(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApprovalApiRequestContext
                        .resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        int effectiveLimit = validateLimit(limit, correlationId);
        InboxPosition position = decodeInbox(
                cursor, actor, correlationId);
        var page = queries.inbox(
                actor.tenant(),
                actor.identityId(),
                position,
                effectiveLimit);
        return ResponseEntity.ok()
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ApprovalInboxPage(
                        page.items().stream()
                                .map(GovernanceApprovalController::resource)
                                .toList(),
                        cursors.encodeInbox(
                                actor.tenant(),
                                actor.identityId(),
                                page.nextPosition())));
    }

    @PostMapping("/approval-cases/{approvalCaseId}/approve")
    public ResponseEntity<ApprovalCaseResource> approve(
            @PathVariable UUID approvalCaseId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        return decide(
                approvalCaseId,
                ApprovalDecision.Decision.APPROVE,
                ifMatch,
                idempotencyKey,
                request);
    }

    @PostMapping("/approval-cases/{approvalCaseId}/reject")
    public ResponseEntity<ApprovalCaseResource> reject(
            @PathVariable UUID approvalCaseId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        return decide(
                approvalCaseId,
                ApprovalDecision.Decision.REJECT,
                ifMatch,
                idempotencyKey,
                request);
    }

    private ResponseEntity<ApprovalCaseResource> decide(
            UUID approvalCaseId,
            ApprovalDecision.Decision decision,
            String ifMatch,
            String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApprovalApiRequestContext
                        .resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        long expectedRevision =
                parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        Instant now = Instant.now();
        ApprovalCase updated = mutations.decide(
                actor,
                approvalCaseId,
                decision,
                expectedRevision,
                key,
                fingerprint(
                        "approval:" + decision.name(),
                        approvalCaseId,
                        expectedRevision),
                now,
                correlationId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(updated.revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(updated));
    }

    private InboxPosition decodeInbox(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeInbox(
                    cursor,
                    actor.tenant(),
                    actor.identityId());
        } catch (IllegalArgumentException invalid) {
            throw GovernanceApprovalApiException.validation(
                    correlationId,
                    "cursor",
                    "invalid_cursor",
                    "cursor is invalid or malformed.");
        }
    }

    private static int validateLimit(
            Integer limit,
            UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > 200) {
            throw GovernanceApprovalApiException.validation(
                    correlationId,
                    "limit",
                    "out_of_range",
                    "limit must be between 1 and 200.");
        }
        return value;
    }

    private static long parseIfMatch(
            String value,
            UUID correlationId) {
        if (value == null
                || !value.matches(
                        "\"rev-[1-9][0-9]*\"")) {
            throw GovernanceApprovalApiException.validation(
                    correlationId,
                    "If-Match",
                    "invalid_revision_etag",
                    "If-Match must be a strong revision ETag such as \"rev-7\".");
        }
        try {
            return Long.parseLong(
                    value.substring(5, value.length() - 1));
        } catch (NumberFormatException invalid) {
            throw GovernanceApprovalApiException.validation(
                    correlationId,
                    "If-Match",
                    "invalid_revision_etag",
                    "If-Match revision is outside the supported range.");
        }
    }

    private static String validateIdempotencyKey(
            String key,
            UUID correlationId) {
        if (key == null
                || key.isBlank()
                || key.length() < 8
                || key.length() > 200) {
            throw GovernanceApprovalApiException.validation(
                    correlationId,
                    "Idempotency-Key",
                    "invalid_length",
                    "Idempotency-Key must contain between 8 and 200 characters.");
        }
        return key;
    }

    private static RequestFingerprint fingerprint(
            Object... values) {
        StringBuilder normalized = new StringBuilder();
        for (Object value : values) {
            normalized.append(
                            value == null
                                    ? "<null>"
                                    : value.toString())
                    .append('\u0000');
        }
        return RequestFingerprint.sha256(
                normalized.toString()
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String etag(long revision) {
        return "\"rev-" + revision + "\"";
    }

    private static ApprovalInboxItemResource resource(
            ApprovalRepository.InboxItem value) {
        return new ApprovalInboxItemResource(
                resource(value.approvalCase()),
                value.stageId(),
                value.decisionMode().name(),
                value.participantIdentityIds());
    }

    private static ApprovalCaseResource resource(
            ApprovalCase value) {
        return new ApprovalCaseResource(
                value.id(),
                value.subjectType().name(),
                value.subjectId(),
                value.subjectRevision(),
                value.requesterIdentityId(),
                value.lifecycleState().name(),
                value.currentStageOrdinal(),
                value.revision(),
                value.createdAt(),
                value.updatedAt(),
                value.completedAt());
    }
}
