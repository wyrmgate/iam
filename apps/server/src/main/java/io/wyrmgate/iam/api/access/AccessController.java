package io.wyrmgate.iam.api.access;

import io.wyrmgate.iam.access.application.AccessAssignmentQueryService;
import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentPosition;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveDetail;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectivePosition;
import io.wyrmgate.iam.access.application.EffectiveAccessReadService;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.access.AccessApiModels.AccessAssignmentPage;
import io.wyrmgate.iam.api.access.AccessApiModels.AccessAssignmentResource;
import io.wyrmgate.iam.api.access.AccessApiModels.EffectiveAccessPage;
import io.wyrmgate.iam.api.access.AccessApiModels.EffectiveAccessResource;
import io.wyrmgate.iam.api.access.AccessApiModels.EffectiveAccessSummary;
import io.wyrmgate.iam.api.access.AccessApiModels.EffectiveAccessSupportResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
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
@RequestMapping("/api/v1")
public class AccessController {

    private static final int DEFAULT_LIMIT = 50;

    private final AccessAssignmentQueryService assignments;
    private final EffectiveAccessReadService effectiveAccess;
    private final AccessApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final AccessCursorCodec cursors;

    public AccessController(
            AccessAssignmentQueryService assignments,
            EffectiveAccessReadService effectiveAccess,
            AccessApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            AccessCursorCodec cursors) {
        this.assignments = assignments;
        this.effectiveAccess = effectiveAccess;
        this.mutations = mutations;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping("/access-assignments")
    public ResponseEntity<AccessAssignmentPage> listAssignments(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                AccessApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        require(
                actor,
                AdministrativePermissions.ACCESS_ASSIGNMENT_READ,
                AdministrativeResource.collection("access-assignment"),
                correlationId);
        int effectiveLimit =
                validateLimit(limit, correlationId);
        AssignmentPosition position =
                decodeAssignments(
                        cursor, actor, correlationId);
        var page = assignments.list(
                actor.tenant(), position, effectiveLimit);
        return ResponseEntity.ok()
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AccessAssignmentPage(
                        page.items().stream()
                                .map(AccessController::resource)
                                .toList(),
                        cursors.encodeAssignments(
                                actor.tenant(),
                                page.nextPosition())));
    }

    @PostMapping("/access-assignments")
    public ResponseEntity<AccessAssignmentResource> createAssignment(
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId =
                AccessApiRequestContext.resolveCorrelationId(
                        request, ids);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        Map<String,Object> parsed = exact(
                body,
                Set.of(
                        "identityId",
                        "targetKind",
                        "roleId",
                        "entitlementId",
                        "principalConstraintKind",
                        "specificPrincipalId",
                        "validFrom",
                        "validUntil"),
                correlationId);
        UUID identityId = requiredUuid(
                parsed.get("identityId"),
                "identityId",
                correlationId);
        AccessAssignment.TargetKind targetKind =
                targetKind(
                        parsed.get("targetKind"),
                        correlationId);
        UUID roleId = optionalUuid(
                parsed.get("roleId"),
                "roleId",
                correlationId);
        UUID entitlementId = optionalUuid(
                parsed.get("entitlementId"),
                "entitlementId",
                correlationId);
        if (targetKind
                == AccessAssignment.TargetKind.ROLE) {
            if (roleId == null || entitlementId != null) {
                throw AccessApiException.validation(
                        correlationId,
                        "target",
                        "invalid_shape",
                        "ROLE target requires roleId and no entitlementId.");
            }
        } else if (entitlementId == null || roleId != null) {
            throw AccessApiException.validation(
                    correlationId,
                    "target",
                    "invalid_shape",
                    "ENTITLEMENT target requires entitlementId and no roleId.");
        }

        AccessAssignment.PrincipalConstraintKind constraint =
                constraintKind(
                        parsed.get(
                                "principalConstraintKind"),
                        correlationId);
        UUID specificPrincipalId = optionalUuid(
                parsed.get("specificPrincipalId"),
                "specificPrincipalId",
                correlationId);
        if (constraint
                        == AccessAssignment.PrincipalConstraintKind.SPECIFIC
                && specificPrincipalId == null) {
            throw AccessApiException.validation(
                    correlationId,
                    "specificPrincipalId",
                    "required",
                    "SPECIFIC principal constraint requires specificPrincipalId.");
        }
        if (constraint
                        == AccessAssignment.PrincipalConstraintKind.ANY
                && specificPrincipalId != null) {
            throw AccessApiException.validation(
                    correlationId,
                    "specificPrincipalId",
                    "not_allowed",
                    "ANY principal constraint must not include specificPrincipalId.");
        }
        Instant validFrom = optionalInstant(
                parsed.get("validFrom"),
                "validFrom",
                correlationId);
        Instant validUntil = optionalInstant(
                parsed.get("validUntil"),
                "validUntil",
                correlationId);

        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        AccessAssignment created = mutations.create(
                actor,
                identityId,
                targetKind,
                roleId,
                entitlementId,
                constraint,
                specificPrincipalId,
                validFrom,
                validUntil,
                key,
                fingerprint(
                        "assignment:create",
                        identityId,
                        targetKind,
                        roleId,
                        entitlementId,
                        constraint,
                        specificPrincipalId,
                        validFrom,
                        validUntil),
                Instant.now(),
                correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(
                        HttpHeaders.ETAG,
                        etag(created.revision()))
                .header(
                        HttpHeaders.LOCATION,
                        "/api/v1/access-assignments/"
                                + created.id())
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(created));
    }

    @GetMapping("/access-assignments/{assignmentId}")
    public ResponseEntity<AccessAssignmentResource> getAssignment(
            @PathVariable UUID assignmentId,
            HttpServletRequest request) {
        UUID correlationId =
                AccessApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        require(
                actor,
                AdministrativePermissions.ACCESS_ASSIGNMENT_READ,
                new AdministrativeResource(
                        "access-assignment",
                        assignmentId),
                correlationId);
        AccessAssignment value = assignments.find(
                        actor.tenant(), assignmentId)
                .orElseThrow(() ->
                        AccessApiException.notFound(
                                correlationId));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(value.revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(value));
    }

    @PostMapping(
            "/access-assignments/{assignmentId}/suspend")
    public ResponseEntity<AccessAssignmentResource> suspend(
            @PathVariable UUID assignmentId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        return lifecycle(
                assignmentId,
                "suspend",
                ifMatch,
                idempotencyKey,
                request);
    }

    @PostMapping(
            "/access-assignments/{assignmentId}/resume")
    public ResponseEntity<AccessAssignmentResource> resume(
            @PathVariable UUID assignmentId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        return lifecycle(
                assignmentId,
                "resume",
                ifMatch,
                idempotencyKey,
                request);
    }

    @PostMapping(
            "/access-assignments/{assignmentId}/cancel")
    public ResponseEntity<AccessAssignmentResource> cancel(
            @PathVariable UUID assignmentId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        return lifecycle(
                assignmentId,
                "cancel",
                ifMatch,
                idempotencyKey,
                request);
    }

    @PostMapping(
            "/access-assignments/{assignmentId}/revoke")
    public ResponseEntity<AccessAssignmentResource> revoke(
            @PathVariable UUID assignmentId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        return lifecycle(
                assignmentId,
                "revoke",
                ifMatch,
                idempotencyKey,
                request);
    }

    @GetMapping("/effective-access")
    public ResponseEntity<EffectiveAccessPage> listEffectiveAccess(
            @RequestParam(required = false) UUID identityId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                AccessApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        require(
                actor,
                AdministrativePermissions.EFFECTIVE_ACCESS_READ,
                AdministrativeResource.collection(
                        "effective-access"),
                correlationId);
        int effectiveLimit =
                validateLimit(limit, correlationId);
        EffectivePosition position = decodeEffective(
                cursor,
                actor,
                identityId,
                correlationId);
        var page = effectiveAccess.list(
                actor.tenant(),
                identityId,
                position,
                effectiveLimit,
                Instant.now());
        return ResponseEntity.ok()
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new EffectiveAccessPage(
                        page.items().stream()
                                .map(AccessController::summary)
                                .toList(),
                        cursors.encodeEffective(
                                actor.tenant(),
                                identityId,
                                page.nextPosition())));
    }

    @GetMapping("/effective-access/{effectiveAccessId}")
    public ResponseEntity<EffectiveAccessResource> getEffectiveAccess(
            @PathVariable UUID effectiveAccessId,
            HttpServletRequest request) {
        UUID correlationId =
                AccessApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        require(
                actor,
                AdministrativePermissions.EFFECTIVE_ACCESS_READ,
                new AdministrativeResource(
                        "effective-access",
                        effectiveAccessId),
                correlationId);
        EffectiveDetail detail = effectiveAccess.find(
                        actor.tenant(),
                        effectiveAccessId,
                        Instant.now())
                .orElseThrow(() ->
                        AccessApiException.notFound(
                                correlationId));
        return ResponseEntity.ok()
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(detail));
    }

    private ResponseEntity<AccessAssignmentResource> lifecycle(
            UUID assignmentId,
            String action,
            String ifMatch,
            String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId =
                AccessApiRequestContext.resolveCorrelationId(
                        request, ids);
        long revision =
                parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        RequestFingerprint fingerprint = fingerprint(
                "assignment:" + action,
                assignmentId,
                revision);
        AccessAssignment value = switch (action) {
            case "suspend" -> mutations.suspend(
                    actor,
                    assignmentId,
                    revision,
                    key,
                    fingerprint,
                    now,
                    correlationId);
            case "resume" -> mutations.resume(
                    actor,
                    assignmentId,
                    revision,
                    key,
                    fingerprint,
                    now,
                    correlationId);
            case "cancel" -> mutations.cancel(
                    actor,
                    assignmentId,
                    revision,
                    key,
                    fingerprint,
                    now,
                    correlationId);
            case "revoke" -> mutations.revoke(
                    actor,
                    assignmentId,
                    revision,
                    key,
                    fingerprint,
                    now,
                    correlationId);
            default -> throw new IllegalArgumentException(
                    "unsupported lifecycle operation");
        };
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(value.revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(value));
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(
                actor,
                permission,
                resource,
                Instant.now()).allowed()) {
            throw AccessApiException.forbidden(correlationId);
        }
    }

    private AssignmentPosition decodeAssignments(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeAssignments(
                    cursor, actor.tenant());
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private EffectivePosition decodeEffective(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID identityId,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeEffective(
                    cursor,
                    actor.tenant(),
                    identityId);
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private static AccessApiException invalidCursor(
            UUID correlationId) {
        return AccessApiException.validation(
                correlationId,
                "cursor",
                "invalid_cursor",
                "cursor is invalid or malformed.");
    }

    private static int validateLimit(
            Integer limit, UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > 200) {
            throw AccessApiException.validation(
                    correlationId,
                    "limit",
                    "out_of_range",
                    "limit must be between 1 and 200.");
        }
        return value;
    }

    private static long parseIfMatch(
            String value, UUID correlationId) {
        if (value == null
                || !value.matches(
                        "\\\"rev-[1-9][0-9]*\\\"")) {
            throw AccessApiException.validation(
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
            throw AccessApiException.validation(
                    correlationId,
                    "If-Match",
                    "invalid_revision_etag",
                    "If-Match revision is outside the supported range.");
        }
    }

    private static String validateIdempotencyKey(
            String key, UUID correlationId) {
        if (key == null
                || key.isBlank()
                || key.length() < 8
                || key.length() > 200) {
            throw AccessApiException.validation(
                    correlationId,
                    "Idempotency-Key",
                    "invalid_length",
                    "Idempotency-Key must contain between 8 and 200 characters.");
        }
        return key;
    }

    private static Map<String,Object> exact(
            Map<String,Object> body,
            Set<String> fields,
            UUID correlationId) {
        if (body == null
                || !body.keySet().equals(fields)) {
            throw AccessApiException.validation(
                    correlationId,
                    "request",
                    "unexpected_fields",
                    "Request body fields do not match the operation contract.");
        }
        return new LinkedHashMap<>(body);
    }

    private static UUID requiredUuid(
            Object value,
            String field,
            UUID correlationId) {
        UUID parsed = optionalUuid(
                value, field, correlationId);
        if (parsed == null) {
            throw AccessApiException.validation(
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
            throw AccessApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string or null.");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw AccessApiException.validation(
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
            throw AccessApiException.validation(
                    correlationId,
                    field,
                    "invalid_datetime",
                    field + " must be an RFC3339 instant or null.");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            throw AccessApiException.validation(
                    correlationId,
                    field,
                    "invalid_datetime",
                    field + " must be an RFC3339 instant or null.");
        }
    }

    private static AccessAssignment.TargetKind targetKind(
            Object value, UUID correlationId) {
        if (!(value instanceof String text)) {
            throw AccessApiException.validation(
                    correlationId,
                    "targetKind",
                    "invalid_enum",
                    "targetKind must be ROLE or ENTITLEMENT.");
        }
        try {
            return AccessAssignment.TargetKind.valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw AccessApiException.validation(
                    correlationId,
                    "targetKind",
                    "invalid_enum",
                    "targetKind must be ROLE or ENTITLEMENT.");
        }
    }

    private static AccessAssignment.PrincipalConstraintKind
            constraintKind(
                    Object value,
                    UUID correlationId) {
        if (!(value instanceof String text)) {
            throw AccessApiException.validation(
                    correlationId,
                    "principalConstraintKind",
                    "invalid_enum",
                    "principalConstraintKind must be ANY or SPECIFIC.");
        }
        try {
            return AccessAssignment.PrincipalConstraintKind
                    .valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw AccessApiException.validation(
                    correlationId,
                    "principalConstraintKind",
                    "invalid_enum",
                    "principalConstraintKind must be ANY or SPECIFIC.");
        }
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

    private static AccessAssignmentResource resource(
            AccessAssignment value) {
        return new AccessAssignmentResource(
                value.id(),
                value.identityId(),
                value.targetKind().name(),
                value.roleId(),
                value.entitlementId(),
                value.principalConstraintKind().name(),
                value.specificPrincipalId(),
                value.provenanceKind().name(),
                value.provenanceRefId(),
                value.lifecycleState().name(),
                value.validFrom(),
                value.validUntil(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private static EffectiveAccessSummary summary(
            EffectiveAccess value) {
        return new EffectiveAccessSummary(
                value.id(),
                value.identityId(),
                value.entitlementId(),
                value.principalConstraintKey(),
                value.supportCount(),
                value.computedAt(),
                value.projectionGeneration());
    }

    private static EffectiveAccessResource resource(
            EffectiveDetail detail) {
        EffectiveAccess value = detail.effectiveAccess();
        return new EffectiveAccessResource(
                value.id(),
                value.identityId(),
                value.entitlementId(),
                value.principalConstraintKey(),
                value.supportCount(),
                value.computedAt(),
                value.projectionGeneration(),
                detail.supports().stream()
                        .map(s -> new EffectiveAccessSupportResource(
                                s.accessAssignmentId(),
                                s.pathHash(),
                                s.pathDepth(),
                                s.roleVersionPath()))
                        .toList());
    }
}
