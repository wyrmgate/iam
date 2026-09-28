package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.AccessRequestResource;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalApproverResource;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalDecisionResource;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalEvidenceResource;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalInboxItem;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalInboxPage;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalStageResource;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.RequestItemResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemSpec;
import io.wyrmgate.iam.governance.application.AccessRequestModels.PrincipalConstraintKind;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestDetail;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.governance.application.AccessRequestModels.TargetKind;
import io.wyrmgate.iam.governance.application.AccessRequestRepository;
import io.wyrmgate.iam.governance.application.ApprovalModels.ApprovalCase;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionValue;
import io.wyrmgate.iam.governance.application.ApprovalReadModels.CaseEvidence;
import io.wyrmgate.iam.governance.application.ApprovalReadService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
@RequestMapping("/api/v1")
public class GovernanceController {

    private static final int DEFAULT_LIMIT = 50;

    private final AccessRequestRepository requests;
    private final ApprovalReadService approvalReads;
    private final GovernanceApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final GovernanceCursorCodec cursors;

    public GovernanceController(
            AccessRequestRepository requests,
            ApprovalReadService approvalReads,
            GovernanceApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            GovernanceCursorCodec cursors) {
        this.requests = Objects.requireNonNull(
                requests, "requests");
        this.approvalReads = Objects.requireNonNull(
                approvalReads, "approvalReads");
        this.mutations = Objects.requireNonNull(
                mutations, "mutations");
        this.authorization = Objects.requireNonNull(
                authorization, "authorization");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.cursors = Objects.requireNonNull(
                cursors, "cursors");
    }

    @PostMapping("/access-requests")
    public ResponseEntity<AccessRequestResource> create(
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody CreateAccessRequestBody body,
            HttpServletRequest request) {
        UUID correlationId = GovernanceApiRequestContext
                .resolveCorrelationId(request, ids);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        if (body == null
                || body.beneficiaryIdentityId() == null
                || body.items() == null
                || body.items().isEmpty()) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "body",
                    "required",
                    "beneficiaryIdentityId and at least one item are required.");
        }
        List<ItemSpec> items = body.items().stream()
                .map(item -> itemSpec(
                        item, correlationId))
                .toList();
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        RequestFingerprint fingerprint = fingerprint(
                "access-request:create",
                actor.identityId(),
                body.beneficiaryIdentityId(),
                items);
        RequestDetail created = mutations.create(
                actor,
                body.beneficiaryIdentityId(),
                items,
                key,
                fingerprint,
                Instant.now(),
                correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(
                        HttpHeaders.LOCATION,
                        "/api/v1/access-requests/"
                                + created.request().id())
                .header(
                        HttpHeaders.ETAG,
                        etag(created.request().revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(created));
    }

    @GetMapping("/access-requests/{requestId}")
    public ResponseEntity<AccessRequestResource> getRequest(
            @PathVariable UUID requestId,
            HttpServletRequest request) {
        UUID correlationId = GovernanceApiRequestContext
                .resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        RequestDetail detail = visibleRequest(
                actor, requestId, correlationId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(detail.request().revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(detail));
    }

    @GetMapping("/request-items/{itemId}")
    public ResponseEntity<RequestItemResource> getItem(
            @PathVariable UUID itemId,
            HttpServletRequest request) {
        UUID correlationId = GovernanceApiRequestContext
                .resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        RequestItem item = requests.findItem(
                        actor.tenant(), itemId)
                .orElseThrow(() ->
                        GovernanceApiException.notFound(
                                correlationId));
        visibleRequest(
                actor,
                item.accessRequestId(),
                correlationId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(item.revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(item));
    }

    @PostMapping("/access-requests/{requestId}/submit")
    public ResponseEntity<AccessRequestResource> submit(
            @PathVariable UUID requestId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId = GovernanceApiRequestContext
                .resolveCorrelationId(request, ids);
        long revision = parseIfMatch(
                ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        RequestDetail submitted = mutations.submit(
                actor,
                requestId,
                revision,
                key,
                fingerprint(
                        "access-request:submit",
                        actor.identityId(),
                        requestId,
                        revision),
                Instant.now(),
                correlationId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(submitted.request().revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(submitted));
    }

    @GetMapping("/approval-inbox")
    public ResponseEntity<ApprovalInboxPage> inbox(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = GovernanceApiRequestContext
                .resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        int effectiveLimit = limit == null
                ? DEFAULT_LIMIT
                : limit;
        if (effectiveLimit < 1
                || effectiveLimit > 200) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "limit",
                    "out_of_range",
                    "limit must be between 1 and 200.");
        }
        var position = cursor == null
                ? null
                : decodeCursor(
                        cursor, actor, correlationId);
        var page = approvalReads.inbox(
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
                .body(new ApprovalInboxPage(
                        page.items().stream()
                                .map(
                                        GovernanceController
                                                ::inboxItem)
                                .toList(),
                        cursors.encodeInbox(
                                actor.tenant(),
                                actor.identityId(),
                                page.nextPosition())));
    }

    @GetMapping("/approval-cases/{caseId}")
    public ResponseEntity<ApprovalEvidenceResource> evidence(
            @PathVariable UUID caseId,
            HttpServletRequest request) {
        UUID correlationId = GovernanceApiRequestContext
                .resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        CaseEvidence evidence = approvalReads
                .visibleEvidence(
                        actor.tenant(),
                        caseId,
                        actor.identityId())
                .orElseThrow(() ->
                        GovernanceApiException.notFound(
                                correlationId));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(evidence.approvalCase()
                                .revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(evidence(evidence));
    }

    @PostMapping("/approval-cases/{caseId}/approve")
    public ResponseEntity<ApprovalEvidenceResource> approve(
            @PathVariable UUID caseId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody(required = false)
                    DecisionBody body,
            HttpServletRequest request) {
        return decide(
                caseId,
                DecisionValue.APPROVE,
                ifMatch,
                idempotencyKey,
                body,
                request);
    }

    @PostMapping("/approval-cases/{caseId}/reject")
    public ResponseEntity<ApprovalEvidenceResource> reject(
            @PathVariable UUID caseId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody(required = false)
                    DecisionBody body,
            HttpServletRequest request) {
        return decide(
                caseId,
                DecisionValue.REJECT,
                ifMatch,
                idempotencyKey,
                body,
                request);
    }

    private ResponseEntity<ApprovalEvidenceResource> decide(
            UUID caseId,
            DecisionValue decision,
            String ifMatch,
            String idempotencyKey,
            DecisionBody body,
            HttpServletRequest request) {
        UUID correlationId = GovernanceApiRequestContext
                .resolveCorrelationId(request, ids);
        long revision = parseIfMatch(
                ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        String reason = body == null
                ? null
                : body.reason();
        if (reason != null
                && reason.length() > 1000) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "reason",
                    "too_long",
                    "reason must be at most 1000 characters.");
        }
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(
                        request);
        mutations.decide(
                actor,
                caseId,
                decision,
                reason,
                revision,
                key,
                fingerprint(
                        "approval:decision",
                        actor.identityId(),
                        caseId,
                        decision,
                        revision,
                        reason == null ? "" : reason.trim()),
                Instant.now(),
                correlationId);
        CaseEvidence evidence = approvalReads
                .visibleEvidence(
                        actor.tenant(),
                        caseId,
                        actor.identityId())
                .orElseThrow(() ->
                        GovernanceApiException.notFound(
                                correlationId));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(evidence.approvalCase()
                                .revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(evidence(evidence));
    }

    private RequestDetail visibleRequest(
            AuthenticatedAdministrativeActor actor,
            UUID requestId,
            UUID correlationId) {
        var value = requests.findRequest(
                        actor.tenant(), requestId)
                .orElseThrow(() ->
                        GovernanceApiException.notFound(
                                correlationId));
        if (!actor.identityId().equals(
                        value.requesterIdentityId())
                && !actor.identityId().equals(
                        value.beneficiaryIdentityId())
                && !authorization.authorize(
                        actor,
                        AdministrativePermissions
                                .ACCESS_REQUEST_READ,
                        new AdministrativeResource(
                                "access-request",
                                requestId),
                        Instant.now()).allowed()) {
            throw GovernanceApiException.forbidden(
                    correlationId);
        }
        return new RequestDetail(
                value,
                requests.findItems(
                        actor.tenant(), requestId));
    }

    private ApprovalReadModels.InboxPosition decodeCursor(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID correlationId) {
        try {
            return cursors.decodeInbox(
                    cursor,
                    actor.tenant(),
                    actor.identityId());
        } catch (IllegalArgumentException invalid) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "cursor",
                    "invalid",
                    "The cursor is invalid or expired.");
        }
    }

    private static ItemSpec itemSpec(
            CreateRequestItemBody item,
            UUID correlationId) {
        if (item == null
                || item.targetKind() == null
                || item.targetId() == null
                || item.principalConstraintKind()
                        == null) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "items",
                    "invalid",
                    "Each item requires targetKind, targetId and principalConstraintKind.");
        }
        TargetKind targetKind;
        PrincipalConstraintKind constraint;
        try {
            targetKind = TargetKind.valueOf(
                    item.targetKind());
            constraint = PrincipalConstraintKind.valueOf(
                    item.principalConstraintKind());
        } catch (IllegalArgumentException invalid) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "items",
                    "invalid_enum",
                    "targetKind or principalConstraintKind is invalid.");
        }
        try {
            return new ItemSpec(
                    targetKind,
                    item.targetId(),
                    constraint,
                    item.specificPrincipalId(),
                    item.validFrom(),
                    item.validUntil());
        } catch (IllegalArgumentException invalid) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "items",
                    "invalid_shape",
                    invalid.getMessage());
        }
    }

    private static AccessRequestResource resource(
            RequestDetail detail) {
        return new AccessRequestResource(
                detail.request().id(),
                detail.request().requesterIdentityId(),
                detail.request().beneficiaryIdentityId(),
                detail.request().state().name(),
                detail.request().revision(),
                detail.request().createdAt(),
                detail.request().submittedAt(),
                detail.request().updatedAt(),
                detail.items().stream()
                        .map(GovernanceController::resource)
                        .toList());
    }

    private static RequestItemResource resource(
            RequestItem item) {
        return new RequestItemResource(
                item.id(),
                item.accessRequestId(),
                item.targetKind().name(),
                item.roleId(),
                item.entitlementId(),
                item.principalConstraintKind().name(),
                item.specificPrincipalId(),
                item.validFrom(),
                item.validUntil(),
                item.state().name(),
                item.approvalCaseId(),
                item.accessAssignmentId(),
                item.evaluationCode(),
                item.revision(),
                item.createdAt(),
                item.updatedAt());
    }

    private static ApprovalInboxItem inboxItem(
            ApprovalCase value) {
        return new ApprovalInboxItem(
                value.id(),
                value.subjectKind().name(),
                value.subjectId(),
                value.state().name(),
                value.currentStageOrdinal(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private static ApprovalEvidenceResource evidence(
            CaseEvidence value) {
        return new ApprovalEvidenceResource(
                value.approvalCase().id(),
                value.approvalCase()
                        .subjectKind().name(),
                value.approvalCase().subjectId(),
                value.approvalCase()
                        .initiatorIdentityId(),
                value.approvalCase().state().name(),
                value.approvalCase()
                        .currentStageOrdinal(),
                value.approvalCase().revision(),
                value.approvalCase().createdAt(),
                value.approvalCase().updatedAt(),
                value.approvalCase().completedAt(),
                value.plan().id(),
                value.plan().planNumber(),
                value.plan().contentHash(),
                value.plan().createdAt(),
                value.stages().stream()
                        .map(stage ->
                                new ApprovalStageResource(
                                        stage.stage().id(),
                                        stage.stage().ordinal(),
                                        stage.stage()
                                                .decisionMode()
                                                .name(),
                                        stage.stage().createdAt(),
                                        stage.approvers()
                                                .stream()
                                                .map(a ->
                                                        new ApprovalApproverResource(
                                                                a.approverIdentityId(),
                                                                a.createdAt()))
                                                .toList(),
                                        stage.decisions()
                                                .stream()
                                                .map(d ->
                                                        new ApprovalDecisionResource(
                                                                d.id(),
                                                                d.approverIdentityId(),
                                                                d.decision()
                                                                        .name(),
                                                                d.reason(),
                                                                d.decidedAt()))
                                                .toList()))
                        .toList());
    }

    private static long parseIfMatch(
            String value,
            UUID correlationId) {
        if (value == null
                || !value.matches(
                        "\\"rev-[1-9][0-9]*\\"")) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "If-Match",
                    "required_revision",
                    "If-Match must be a revision ETag.");
        }
        return Long.parseLong(
                value.substring(5, value.length() - 1));
    }

    private static String validateIdempotencyKey(
            String value,
            UUID correlationId) {
        if (value == null
                || value.length() < 8
                || value.length() > 200
                || value.isBlank()) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "Idempotency-Key",
                    "invalid",
                    "Idempotency-Key must contain 8 to 200 characters.");
        }
        return value;
    }

    private static String etag(long revision) {
        return "\"rev-" + revision + "\"";
    }

    private static RequestFingerprint fingerprint(
            Object... values) {
        StringBuilder canonical =
                new StringBuilder();
        for (Object value : values) {
            canonical.append(
                    value == null
                            ? "<null>"
                            : value.toString())
                    .append('\u0000');
        }
        return RequestFingerprint.sha256(
                canonical.toString()
                        .getBytes(StandardCharsets.UTF_8));
    }

    public record CreateAccessRequestBody(
            UUID beneficiaryIdentityId,
            List<CreateRequestItemBody> items) {}

    public record CreateRequestItemBody(
            String targetKind,
            UUID targetId,
            String principalConstraintKind,
            UUID specificPrincipalId,
            Instant validFrom,
            Instant validUntil) {}

    public record DecisionBody(String reason) {}
}
