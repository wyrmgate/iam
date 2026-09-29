package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.governance.GovernanceReviewApiModels.*;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.governance.application.ReviewQueryModels.ItemEvidence;
import io.wyrmgate.iam.governance.application.ReviewQueryModels.Position;
import io.wyrmgate.iam.governance.application.ReviewQueryService;
import io.wyrmgate.iam.governance.application.ReviewRepository;
import io.wyrmgate.iam.governance.domain.ReviewModels.DecisionValue;
import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewCampaign;
import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewDecision;
import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewItem;
import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewRemediation;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance")
public final class GovernanceReviewController {

    private static final int DEFAULT_LIMIT = 50;

    private final ReviewRepository reviews;
    private final ReviewQueryService queries;
    private final GovernanceReviewApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final GovernanceCursorCodec cursors;

    public GovernanceReviewController(
            ReviewRepository reviews,
            ReviewQueryService queries,
            GovernanceReviewApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            GovernanceCursorCodec cursors) {
        this.reviews = reviews;
        this.queries = queries;
        this.mutations = mutations;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @PostMapping("/review-campaigns")
    public ResponseEntity<ReviewCampaignResource> createCampaign(
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        Map<String,Object> parsed = exact(
                body,
                Set.of(
                        "subjectIdentityId",
                        "reviewerIdentityId",
                        "snapshotAt"),
                correlationId);
        UUID subjectId = requiredUuid(
                parsed.get("subjectIdentityId"),
                "subjectIdentityId",
                correlationId);
        UUID reviewerId = requiredUuid(
                parsed.get("reviewerIdentityId"),
                "reviewerIdentityId",
                correlationId);
        Instant snapshotAt = requiredInstant(
                parsed.get("snapshotAt"),
                "snapshotAt",
                correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        ReviewCampaign created =
                mutations.createCampaign(
                        actor,
                        subjectId,
                        reviewerId,
                        snapshotAt,
                        key,
                        fingerprint(
                                "review-campaign:create",
                                subjectId,
                                reviewerId,
                                snapshotAt),
                        Instant.now(),
                        correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(
                        HttpHeaders.ETAG,
                        etag(created.revision()))
                .header(
                        HttpHeaders.LOCATION,
                        "/api/v1/governance/review-campaigns/"
                                + created.id())
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(created));
    }

    @GetMapping("/review-campaigns")
    public ResponseEntity<ReviewCampaignPage> campaigns(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        requireAdmin(
                actor,
                AdministrativePermissions.REVIEW_CAMPAIGN_READ,
                AdministrativeResource.collection(
                        "review-campaign"),
                correlationId);
        int effectiveLimit = validateLimit(
                limit, correlationId);
        Position position = decodeCampaigns(
                cursor, actor, correlationId);
        var page = queries.campaigns(
                actor.tenant(),
                position,
                effectiveLimit);
        return ResponseEntity.ok()
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(new ReviewCampaignPage(
                        page.items().stream()
                                .map(GovernanceReviewController::resource)
                                .toList(),
                        cursors.encodeReviewCampaigns(
                                actor.tenant(),
                                page.nextPosition())));
    }

    @GetMapping("/review-campaigns/{campaignId}")
    public ResponseEntity<ReviewCampaignResource> campaign(
            @PathVariable UUID campaignId,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        ReviewCampaign campaign = requireCampaign(
                actor, campaignId, correlationId);
        requireCampaignRead(
                actor, campaign, correlationId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(campaign.revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(campaign));
    }

    @PostMapping("/review-campaigns/{campaignId}/start")
    public ResponseEntity<ReviewCampaignResource> startCampaign(
            @PathVariable UUID campaignId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        long revision = parseIfMatch(
                ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        ReviewCampaign started =
                mutations.startCampaign(
                        actor,
                        campaignId,
                        revision,
                        key,
                        fingerprint(
                                "review-campaign:start",
                                campaignId,
                                revision),
                        Instant.now(),
                        correlationId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(started.revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(started));
    }

    @GetMapping("/review-campaigns/{campaignId}/items")
    public ResponseEntity<ReviewItemPage> campaignItems(
            @PathVariable UUID campaignId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        ReviewCampaign campaign = requireCampaign(
                actor, campaignId, correlationId);
        requireCampaignRead(
                actor, campaign, correlationId);
        int effectiveLimit = validateLimit(
                limit, correlationId);
        Position position = decodeCampaignItems(
                cursor,
                actor,
                campaignId,
                correlationId);
        var page = queries.campaignItems(
                actor.tenant(),
                campaignId,
                position,
                effectiveLimit);
        return ResponseEntity.ok()
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(new ReviewItemPage(
                        page.items().stream()
                                .map(GovernanceReviewController::resource)
                                .toList(),
                        cursors.encodeReviewCampaignItems(
                                actor.tenant(),
                                campaignId,
                                page.nextPosition())));
    }

    @GetMapping("/review-inbox")
    public ResponseEntity<ReviewItemPage> reviewInbox(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        int effectiveLimit = validateLimit(
                limit, correlationId);
        Position position = decodeReviewInbox(
                cursor, actor, correlationId);
        var page = queries.reviewerInbox(
                actor.tenant(),
                actor.identityId(),
                position,
                effectiveLimit);
        return ResponseEntity.ok()
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(new ReviewItemPage(
                        page.items().stream()
                                .map(GovernanceReviewController::resource)
                                .toList(),
                        cursors.encodeReviewInbox(
                                actor.tenant(),
                                actor.identityId(),
                                page.nextPosition())));
    }

    @GetMapping("/review-items/{reviewItemId}")
    public ResponseEntity<ReviewItemResource> reviewItem(
            @PathVariable UUID reviewItemId,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        ItemEvidence evidence = queries.itemEvidence(
                        actor.tenant(),
                        reviewItemId)
                .orElseThrow(() ->
                        GovernanceApiException.notFound(
                                correlationId));
        ReviewCampaign campaign = requireCampaign(
                actor,
                evidence.item().reviewCampaignId(),
                correlationId);
        requireCampaignRead(
                actor, campaign, correlationId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(evidence.item().revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(evidence));
    }

    @PostMapping("/review-items/{reviewItemId}/keep")
    public ResponseEntity<ReviewItemResource> keep(
            @PathVariable UUID reviewItemId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        return decide(
                reviewItemId,
                DecisionValue.KEEP,
                ifMatch,
                idempotencyKey,
                body,
                request);
    }

    @PostMapping("/review-items/{reviewItemId}/revoke")
    public ResponseEntity<ReviewItemResource> revoke(
            @PathVariable UUID reviewItemId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        return decide(
                reviewItemId,
                DecisionValue.REVOKE,
                ifMatch,
                idempotencyKey,
                body,
                request);
    }

    @GetMapping("/review-remediations/{remediationId}")
    public ResponseEntity<ReviewRemediationResource> remediation(
            @PathVariable UUID remediationId,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        var evidence = queries.remediationEvidence(
                        actor.tenant(),
                        remediationId)
                .orElseThrow(() ->
                        GovernanceApiException.notFound(
                                correlationId));
        requireCampaignRead(
                actor,
                evidence.campaign(),
                correlationId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(evidence.remediation()
                                .revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(evidence.remediation()));
    }

    private ResponseEntity<ReviewItemResource> decide(
            UUID reviewItemId,
            DecisionValue decision,
            String ifMatch,
            String idempotencyKey,
            Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        long revision = parseIfMatch(
                ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        Map<String,Object> parsed = optionalReasonBody(
                body,
                correlationId);
        String reason = optionalReason(
                parsed.get("reason"),
                correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        ItemEvidence evidence = mutations.decide(
                actor,
                reviewItemId,
                decision,
                reason,
                revision,
                key,
                fingerprint(
                        "review-item:decision",
                        reviewItemId,
                        decision,
                        reason,
                        revision),
                Instant.now(),
                correlationId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(evidence.item().revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(evidence));
    }

    private ReviewCampaign requireCampaign(
            AuthenticatedAdministrativeActor actor,
            UUID campaignId,
            UUID correlationId) {
        return reviews.findCampaign(
                        actor.tenant(), campaignId)
                .orElseThrow(() ->
                        GovernanceApiException.notFound(
                                correlationId));
    }

    private void requireCampaignRead(
            AuthenticatedAdministrativeActor actor,
            ReviewCampaign campaign,
            UUID correlationId) {
        if (campaign.reviewerIdentityId()
                .equals(actor.identityId())) {
            return;
        }
        requireAdmin(
                actor,
                AdministrativePermissions.REVIEW_CAMPAIGN_READ,
                new AdministrativeResource(
                        "review-campaign",
                        campaign.id()),
                correlationId);
    }

    private void requireAdmin(
            AuthenticatedAdministrativeActor actor,
            io.wyrmgate.iam.administration.domain
                    .AdministrativePermission permission,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(
                actor,
                permission,
                resource,
                Instant.now()).allowed()) {
            throw GovernanceApiException.forbidden(
                    correlationId);
        }
    }

    private Position decodeCampaigns(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeReviewCampaigns(
                    cursor, actor.tenant());
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private Position decodeCampaignItems(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID campaignId,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeReviewCampaignItems(
                    cursor,
                    actor.tenant(),
                    campaignId);
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private Position decodeReviewInbox(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeReviewInbox(
                    cursor,
                    actor.tenant(),
                    actor.identityId());
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private static GovernanceApiException invalidCursor(
            UUID correlationId) {
        return GovernanceApiException.validation(
                correlationId,
                "cursor",
                "invalid_cursor",
                "cursor is invalid or malformed.");
    }

    private static Map<String,Object> optionalReasonBody(
            Map<String,Object> body,
            UUID correlationId) {
        if (body == null
                || !(body.isEmpty()
                || body.keySet().equals(Set.of("reason")))) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "request",
                    "unexpected_fields",
                    "Decision body may contain only the optional reason field.");
        }
        return new LinkedHashMap<>(body);
    }

    private static Map<String,Object> exact(
            Map<String,Object> body,
            Set<String> fields,
            UUID correlationId) {
        if (body == null
                || !body.keySet().equals(fields)) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "request",
                    "unexpected_fields",
                    "Request body fields do not match the operation contract.");
        }
        return new LinkedHashMap<>(body);
    }

    private static int validateLimit(
            Integer limit,
            UUID correlationId) {
        int value = limit == null
                ? DEFAULT_LIMIT
                : limit;
        if (value < 1 || value > 200) {
            throw GovernanceApiException.validation(
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
            throw GovernanceApiException.validation(
                    correlationId,
                    "If-Match",
                    "invalid_revision_etag",
                    "If-Match must be a strong revision ETag such as \"rev-7\".");
        }
        try {
            return Long.parseLong(
                    value.substring(
                            5, value.length() - 1));
        } catch (NumberFormatException invalid) {
            throw GovernanceApiException.validation(
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
            throw GovernanceApiException.validation(
                    correlationId,
                    "Idempotency-Key",
                    "invalid_length",
                    "Idempotency-Key must contain between 8 and 200 characters.");
        }
        return key;
    }

    private static UUID requiredUuid(
            Object value,
            String field,
            UUID correlationId) {
        if (!(value instanceof String text)) {
            throw GovernanceApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string.");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw GovernanceApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string.");
        }
    }

    private static Instant requiredInstant(
            Object value,
            String field,
            UUID correlationId) {
        if (!(value instanceof String text)) {
            throw GovernanceApiException.validation(
                    correlationId,
                    field,
                    "invalid_datetime",
                    field + " must be an RFC3339 instant.");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            throw GovernanceApiException.validation(
                    correlationId,
                    field,
                    "invalid_datetime",
                    field + " must be an RFC3339 instant.");
        }
    }

    private static String optionalReason(
            Object value,
            UUID correlationId) {
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "reason",
                    "invalid_string",
                    "reason must be a string or null.");
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 1000) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "reason",
                    "too_long",
                    "reason must be at most 1000 characters.");
        }
        return normalized;
    }

    private static RequestFingerprint fingerprint(
            Object... values) {
        StringBuilder normalized =
                new StringBuilder();
        for (Object value : values) {
            normalized.append(
                            value == null
                                    ? "<null>"
                                    : value.toString())
                    .append('\u0000');
        }
        return RequestFingerprint.sha256(
                normalized.toString()
                        .getBytes(
                                StandardCharsets.UTF_8));
    }

    private static String etag(long revision) {
        return "\"rev-" + revision + "\"";
    }

    private static ReviewCampaignResource resource(
            ReviewCampaign value) {
        return new ReviewCampaignResource(
                value.id(),
                value.kind().name(),
                value.subjectIdentityId(),
                value.reviewerIdentityId(),
                value.snapshotAt(),
                value.state().name(),
                value.generatedItemCount(),
                value.decidedItemCount(),
                value.revision(),
                value.createdAt(),
                value.updatedAt(),
                value.activatedAt(),
                value.completedAt(),
                value.failureCode());
    }

    private static ReviewItemResource resource(
            ReviewItem value) {
        return resource(
                new ItemEvidence(
                        value, null, null));
    }

    private static ReviewItemResource resource(
            ItemEvidence evidence) {
        ReviewItem value = evidence.item();
        return new ReviewItemResource(
                value.id(),
                value.reviewCampaignId(),
                value.reviewerIdentityId(),
                value.accessAssignmentId(),
                value.assignmentRevision(),
                value.targetKind(),
                value.roleId(),
                value.entitlementId(),
                value.principalConstraintKind(),
                value.specificPrincipalId(),
                value.provenanceKind(),
                value.provenanceRefId(),
                value.snapshotLifecycleState(),
                value.validFrom(),
                value.validUntil(),
                value.assignmentCreatedAt(),
                value.snapshotAt(),
                value.state().name(),
                value.revision(),
                value.createdAt(),
                value.updatedAt(),
                resource(evidence.decision()),
                resource(evidence.remediation()));
    }

    private static ReviewDecisionResource resource(
            ReviewDecision value) {
        return value == null
                ? null
                : new ReviewDecisionResource(
                        value.id(),
                        value.reviewerIdentityId(),
                        value.decision().name(),
                        value.reason(),
                        value.decidedAt());
    }

    private static ReviewRemediationResource resource(
            ReviewRemediation value) {
        return value == null
                ? null
                : new ReviewRemediationResource(
                        value.id(),
                        value.reviewItemId(),
                        value.accessAssignmentId(),
                        value.state().name(),
                        value.resultCode(),
                        value.resultingAccessState(),
                        value.revision(),
                        value.createdAt(),
                        value.updatedAt(),
                        value.completedAt());
    }
}
