package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.AccessRequestResource;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalApproverResource;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalDecisionResource;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalInboxItem;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalInboxPage;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalResource;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.ApprovalStageResource;
import io.wyrmgate.iam.api.governance.GovernanceApiModels.RequestItemResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.governance.application.AccessRequestModels.ItemSpec;
import io.wyrmgate.iam.governance.application.AccessRequestModels.PrincipalConstraintKind;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestDetail;
import io.wyrmgate.iam.governance.application.AccessRequestModels.RequestItem;
import io.wyrmgate.iam.governance.application.AccessRequestModels.TargetKind;
import io.wyrmgate.iam.governance.application.AccessRequestRepository;
import io.wyrmgate.iam.governance.application.ApprovalModels.DecisionValue;
import io.wyrmgate.iam.governance.application.ApprovalQueryModels.ApprovalEvidence;
import io.wyrmgate.iam.governance.application.ApprovalQueryModels.InboxPosition;
import io.wyrmgate.iam.governance.application.ApprovalQueryService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
public class GovernanceController {

    private static final int DEFAULT_LIMIT = 50;

    private final AccessRequestRepository requests;
    private final ApprovalQueryService approvals;
    private final GovernanceApiMutationService mutations;
    private final IdGenerator ids;
    private final GovernanceCursorCodec cursors;

    public GovernanceController(
            AccessRequestRepository requests,
            ApprovalQueryService approvals,
            GovernanceApiMutationService mutations,
            IdGenerator ids,
            GovernanceCursorCodec cursors) {
        this.requests = requests;
        this.approvals = approvals;
        this.mutations = mutations;
        this.ids = ids;
        this.cursors = cursors;
    }

    @PostMapping("/access-requests")
    public ResponseEntity<AccessRequestResource> createRequest(
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
                        "beneficiaryIdentityId",
                        "items"),
                correlationId);
        UUID beneficiaryIdentityId = requiredUuid(
                parsed.get("beneficiaryIdentityId"),
                "beneficiaryIdentityId",
                correlationId);
        List<ItemSpec> items = itemSpecs(
                parsed.get("items"),
                correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        RequestDetail created = mutations.createRequest(
                actor,
                beneficiaryIdentityId,
                items,
                key,
                fingerprint(
                        "access-request:create",
                        actor.identityId(),
                        beneficiaryIdentityId,
                        items),
                now,
                correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(
                        HttpHeaders.ETAG,
                        etag(created.request().revision()))
                .header(
                        HttpHeaders.LOCATION,
                        "/api/v1/governance/access-requests/"
                                + created.request().id())
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(created));
    }

    @GetMapping("/access-requests/{requestId}")
    public ResponseEntity<AccessRequestResource> getRequest(
            @PathVariable UUID requestId,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        var accessRequest = requests.findRequest(
                        actor.tenant(), requestId)
                .orElseThrow(() ->
                        GovernanceApiException.notFound(
                                correlationId));
        requireRequestParticipant(
                actor,
                accessRequest.requesterIdentityId(),
                accessRequest.beneficiaryIdentityId(),
                correlationId);
        RequestDetail detail = new RequestDetail(
                accessRequest,
                requests.findItems(
                        actor.tenant(), requestId));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(accessRequest.revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(detail));
    }

    @PostMapping("/access-requests/{requestId}/submit")
    public ResponseEntity<AccessRequestResource> submitRequest(
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
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        long revision = parseIfMatch(
                ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        RequestDetail submitted = mutations.submitRequest(
                actor,
                requestId,
                revision,
                key,
                fingerprint(
                        "access-request:submit",
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
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(submitted));
    }

    @GetMapping("/request-items/{itemId}")
    public ResponseEntity<RequestItemResource> getRequestItem(
            @PathVariable UUID itemId,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        RequestItem item = requests.findItem(
                        actor.tenant(), itemId)
                .orElseThrow(() ->
                        GovernanceApiException.notFound(
                                correlationId));
        var accessRequest = requests.findRequest(
                        actor.tenant(),
                        item.accessRequestId())
                .orElseThrow(() ->
                        GovernanceApiException.notFound(
                                correlationId));
        requireRequestParticipant(
                actor,
                accessRequest.requesterIdentityId(),
                accessRequest.beneficiaryIdentityId(),
                correlationId);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(item.revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(item));
    }

    @GetMapping("/approval-inbox")
    public ResponseEntity<ApprovalInboxPage> approvalInbox(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        int effectiveLimit = validateLimit(
                limit, correlationId);
        InboxPosition position = decodeInbox(
                cursor, actor, correlationId);
        var page = approvals.inbox(
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
                                .map(GovernanceController::inboxItem)
                                .toList(),
                        cursors.encodeInbox(
                                actor.tenant(),
                                actor.identityId(),
                                page.nextPosition())));
    }

    @GetMapping("/approvals/{approvalCaseId}")
    public ResponseEntity<ApprovalResource> getApproval(
            @PathVariable UUID approvalCaseId,
            HttpServletRequest request) {
        UUID correlationId =
                GovernanceApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        if (!approvals.isParticipant(
                actor.tenant(),
                approvalCaseId,
                actor.identityId())) {
            if (approvals.findCase(
                    actor.tenant(),
                    approvalCaseId).isEmpty()) {
                throw GovernanceApiException.notFound(
                        correlationId);
            }
            throw GovernanceApiException.forbidden(
                    correlationId);
        }
        ApprovalEvidence evidence = approvals.evidence(
                        actor.tenant(),
                        approvalCaseId)
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
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(evidence));
    }

    @PostMapping("/approvals/{approvalCaseId}/approve")
    public ResponseEntity<ApprovalResource> approve(
            @PathVariable UUID approvalCaseId,
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
                approvalCaseId,
                DecisionValue.APPROVE,
                ifMatch,
                idempotencyKey,
                body,
                request);
    }

    @PostMapping("/approvals/{approvalCaseId}/reject")
    public ResponseEntity<ApprovalResource> reject(
            @PathVariable UUID approvalCaseId,
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
                approvalCaseId,
                DecisionValue.REJECT,
                ifMatch,
                idempotencyKey,
                body,
                request);
    }

    private ResponseEntity<ApprovalResource> decide(
            UUID approvalCaseId,
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
        Map<String,Object> parsed = exact(
                body,
                Set.of("reason"),
                correlationId);
        String reason = optionalReason(
                parsed.get("reason"),
                correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        var updated = mutations.decide(
                actor,
                approvalCaseId,
                decision,
                reason,
                revision,
                key,
                fingerprint(
                        "approval:decision",
                        approvalCaseId,
                        decision,
                        reason,
                        revision),
                Instant.now(),
                correlationId);
        ApprovalEvidence evidence = approvals.evidence(
                        actor.tenant(),
                        updated.id())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "updated ApprovalCase evidence does not exist"));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(updated.revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(evidence));
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
            throw GovernanceApiException.validation(
                    correlationId,
                    "cursor",
                    "invalid_cursor",
                    "cursor is invalid or malformed.");
        }
    }

    private static void requireRequestParticipant(
            AuthenticatedAdministrativeActor actor,
            UUID requesterIdentityId,
            UUID beneficiaryIdentityId,
            UUID correlationId) {
        if (!actor.identityId().equals(
                        requesterIdentityId)
                && !actor.identityId().equals(
                        beneficiaryIdentityId)) {
            throw GovernanceApiException.forbidden(
                    correlationId);
        }
    }

    private static List<ItemSpec> itemSpecs(
            Object value,
            UUID correlationId) {
        if (!(value instanceof List<?> values)
                || values.isEmpty()
                || values.size() > 1000) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "items",
                    "invalid_size",
                    "items must contain between 1 and 1000 entries.");
        }
        List<ItemSpec> result =
                new ArrayList<>(values.size());
        int index = 0;
        for (Object raw : values) {
            if (!(raw instanceof Map<?,?> map)) {
                throw GovernanceApiException.validation(
                        correlationId,
                        "items[" + index + "]",
                        "invalid_object",
                        "Each item must be an object.");
            }
            Map<String,Object> item = stringMap(
                    map,
                    "items[" + index + "]",
                    correlationId);
            item = exact(
                    item,
                    Set.of(
                            "targetKind",
                            "targetId",
                            "principalConstraintKind",
                            "specificPrincipalId",
                            "validFrom",
                            "validUntil"),
                    correlationId);
            TargetKind targetKind = targetKind(
                    item.get("targetKind"),
                    correlationId);
            UUID targetId = requiredUuid(
                    item.get("targetId"),
                    "items[" + index + "].targetId",
                    correlationId);
            PrincipalConstraintKind constraint =
                    constraintKind(
                            item.get(
                                    "principalConstraintKind"),
                            correlationId);
            UUID principalId = optionalUuid(
                    item.get("specificPrincipalId"),
                    "items[" + index
                            + "].specificPrincipalId",
                    correlationId);
            if (constraint
                            == PrincipalConstraintKind.SPECIFIC
                    && principalId == null) {
                throw GovernanceApiException.validation(
                        correlationId,
                        "items[" + index
                                + "].specificPrincipalId",
                        "required",
                        "SPECIFIC requires specificPrincipalId.");
            }
            if (constraint
                            == PrincipalConstraintKind.ANY
                    && principalId != null) {
                throw GovernanceApiException.validation(
                        correlationId,
                        "items[" + index
                                + "].specificPrincipalId",
                        "not_allowed",
                        "ANY must not include specificPrincipalId.");
            }
            Instant validFrom = optionalInstant(
                    item.get("validFrom"),
                    "items[" + index + "].validFrom",
                    correlationId);
            Instant validUntil = optionalInstant(
                    item.get("validUntil"),
                    "items[" + index + "].validUntil",
                    correlationId);
            try {
                result.add(new ItemSpec(
                        targetKind,
                        targetId,
                        constraint,
                        principalId,
                        validFrom,
                        validUntil));
            } catch (IllegalArgumentException invalid) {
                throw GovernanceApiException.validation(
                        correlationId,
                        "items[" + index + "]",
                        "invalid_item",
                        "The requested access item is invalid.");
            }
            index++;
        }
        return List.copyOf(result);
    }

    private static Map<String,Object> stringMap(
            Map<?,?> map,
            String field,
            UUID correlationId) {
        Map<String,Object> result =
                new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey()
                    instanceof String key)) {
                throw GovernanceApiException.validation(
                        correlationId,
                        field,
                        "invalid_object",
                        "Object field names must be strings.");
            }
            result.put(key, entry.getValue());
        }
        return result;
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
                            5,
                            value.length() - 1));
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
        UUID parsed = optionalUuid(
                value, field, correlationId);
        if (parsed == null) {
            throw GovernanceApiException.validation(
                    correlationId,
                    field,
                    "required",
                    field + " is required.");
        }
        return parsed;
    }

    private static UUID optionalUuid(
            Object value,
            String field,
            UUID correlationId) {
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw GovernanceApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string or null.");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw GovernanceApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string or null.");
        }
    }

    private static Instant optionalInstant(
            Object value,
            String field,
            UUID correlationId) {
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw GovernanceApiException.validation(
                    correlationId,
                    field,
                    "invalid_datetime",
                    field + " must be an RFC3339 instant or null.");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            throw GovernanceApiException.validation(
                    correlationId,
                    field,
                    "invalid_datetime",
                    field + " must be an RFC3339 instant or null.");
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

    private static TargetKind targetKind(
            Object value,
            UUID correlationId) {
        if (!(value instanceof String text)) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "targetKind",
                    "invalid_enum",
                    "targetKind must be ROLE or ENTITLEMENT.");
        }
        try {
            return TargetKind.valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "targetKind",
                    "invalid_enum",
                    "targetKind must be ROLE or ENTITLEMENT.");
        }
    }

    private static PrincipalConstraintKind constraintKind(
            Object value,
            UUID correlationId) {
        if (!(value instanceof String text)) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "principalConstraintKind",
                    "invalid_enum",
                    "principalConstraintKind must be ANY or SPECIFIC.");
        }
        try {
            return PrincipalConstraintKind.valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw GovernanceApiException.validation(
                    correlationId,
                    "principalConstraintKind",
                    "invalid_enum",
                    "principalConstraintKind must be ANY or SPECIFIC.");
        }
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

    private static AccessRequestResource resource(
            RequestDetail detail) {
        var value = detail.request();
        return new AccessRequestResource(
                value.id(),
                value.requesterIdentityId(),
                value.beneficiaryIdentityId(),
                value.state().name(),
                value.revision(),
                value.createdAt(),
                value.submittedAt(),
                value.updatedAt(),
                detail.items().stream()
                        .map(GovernanceController::resource)
                        .toList());
    }

    private static RequestItemResource resource(
            RequestItem value) {
        return new RequestItemResource(
                value.id(),
                value.accessRequestId(),
                value.targetKind().name(),
                value.roleId(),
                value.entitlementId(),
                value.principalConstraintKind().name(),
                value.specificPrincipalId(),
                value.validFrom(),
                value.validUntil(),
                value.state().name(),
                value.approvalCaseId(),
                value.accessAssignmentId(),
                value.evaluationCode(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private static ApprovalInboxItem inboxItem(
            io.wyrmgate.iam.governance.application
                    .ApprovalModels.ApprovalCase value) {
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

    private static ApprovalResource resource(
            ApprovalEvidence evidence) {
        var approvalCase = evidence.approvalCase();
        var plan = evidence.plan();
        return new ApprovalResource(
                approvalCase.id(),
                approvalCase.subjectKind().name(),
                approvalCase.subjectId(),
                approvalCase.initiatorIdentityId(),
                approvalCase.state().name(),
                approvalCase.currentStageOrdinal(),
                approvalCase.revision(),
                approvalCase.createdAt(),
                approvalCase.updatedAt(),
                approvalCase.completedAt(),
                plan.id(),
                plan.planNumber(),
                plan.createdAt(),
                evidence.stages().stream()
                        .map(stage -> new ApprovalStageResource(
                                stage.stage().id(),
                                stage.stage().ordinal(),
                                stage.stage()
                                        .decisionMode()
                                        .name(),
                                stage.approvers().stream()
                                        .map(a ->
                                                new ApprovalApproverResource(
                                                        a.approverIdentityId()))
                                        .toList(),
                                stage.decisions().stream()
                                        .map(d ->
                                                new ApprovalDecisionResource(
                                                        d.id(),
                                                        d.approverIdentityId(),
                                                        d.decision().name(),
                                                        d.reason(),
                                                        d.decidedAt()))
                                        .toList()))
                        .toList());
    }
}
