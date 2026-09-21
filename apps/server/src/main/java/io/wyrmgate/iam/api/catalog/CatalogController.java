package io.wyrmgate.iam.api.catalog;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.catalog.CatalogApiModels.ApplicationPage;
import io.wyrmgate.iam.api.catalog.CatalogApiModels.ApplicationResource;
import io.wyrmgate.iam.api.catalog.CatalogApiModels.ApplicationTargetPage;
import io.wyrmgate.iam.api.catalog.CatalogApiModels.ApplicationTargetResource;
import io.wyrmgate.iam.api.catalog.CatalogApiModels.EntitlementPage;
import io.wyrmgate.iam.api.catalog.CatalogApiModels.EntitlementResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.domain.Application;
import io.wyrmgate.iam.catalog.domain.ApplicationTarget;
import io.wyrmgate.iam.catalog.domain.Entitlement;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private static final int DEFAULT_LIMIT = 50;

    private final CatalogQueryService queries;
    private final CatalogApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final CatalogCursorCodec cursors;

    public CatalogController(
            CatalogQueryService queries,
            CatalogApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            CatalogCursorCodec cursors) {
        this.queries = queries;
        this.mutations = mutations;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping("/applications")
    public ResponseEntity<ApplicationPage> listApplications(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativePermissions.APPLICATION_READ,
                AdministrativeResource.collection("application"),
                correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        PagePosition position = decodeApplications(cursor, actor, correlationId);
        var page = queries.listApplications(actor.tenant(), position, effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .body(new ApplicationPage(
                        page.items().stream().map(CatalogController::resource).toList(),
                        cursors.encodeApplications(actor.tenant(), page.nextPosition())));
    }

    @PostMapping("/applications")
    public ResponseEntity<ApplicationResource> createApplication(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        Map<String,Object> parsed = exact(body, Set.of("code","name"), correlationId);
        String code = requireText(parsed, "code", 128, correlationId);
        String name = requireText(parsed, "name", 512, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        RequestFingerprint fingerprint = fingerprint("application:create", code, name);
        Application created = mutations.createApplication(
                actor, code, name, key, fingerprint, Instant.now(), correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, etag(created.revision()))
                .header(HttpHeaders.LOCATION, "/api/v1/applications/" + created.id())
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(created));
    }

    @GetMapping("/applications/{applicationId}")
    public ResponseEntity<ApplicationResource> getApplication(
            @PathVariable UUID applicationId,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativePermissions.APPLICATION_READ,
                new AdministrativeResource("application", applicationId),
                correlationId);
        Application application = queries.findApplication(actor.tenant(), applicationId)
                .orElseThrow(() -> CatalogApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(application.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(application));
    }

    @PatchMapping("/applications/{applicationId}")
    public ResponseEntity<ApplicationResource> renameApplication(
            @PathVariable UUID applicationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        Map<String,Object> parsed = exact(body, Set.of("name"), correlationId);
        String name = requireText(parsed, "name", 512, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Application updated = mutations.renameApplication(
                actor, applicationId, name, revision, key,
                fingerprint("application:rename", applicationId, revision, name),
                Instant.now(), correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(updated.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(updated));
    }

    @PostMapping("/applications/{applicationId}/retire")
    public ResponseEntity<ApplicationResource> retireApplication(
            @PathVariable UUID applicationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Application retired = mutations.retireApplication(
                actor, applicationId, revision, key,
                fingerprint("application:retire", applicationId, revision),
                Instant.now(), correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(retired.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(retired));
    }

    @GetMapping("/applications/{applicationId}/targets")
    public ResponseEntity<ApplicationTargetPage> listTargets(
            @PathVariable UUID applicationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativePermissions.APPLICATION_TARGET_READ,
                AdministrativeResource.collection("application-target"),
                correlationId);
        if (queries.findApplication(actor.tenant(), applicationId).isEmpty()) {
            throw CatalogApiException.notFound(correlationId);
        }
        int effectiveLimit = validateLimit(limit, correlationId);
        PagePosition position = decodeTargets(cursor, actor, applicationId, correlationId);
        var page = queries.listTargets(actor.tenant(), applicationId, position, effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .body(new ApplicationTargetPage(
                        page.items().stream().map(CatalogController::resource).toList(),
                        cursors.encodeTargets(actor.tenant(), applicationId, page.nextPosition())));
    }

    @PostMapping("/applications/{applicationId}/targets")
    public ResponseEntity<ApplicationTargetResource> createTarget(
            @PathVariable UUID applicationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        String code = requireText(
                exact(body, Set.of("code"), correlationId), "code", 128, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        ApplicationTarget created = mutations.createTarget(
                actor, applicationId, code, key,
                fingerprint("target:create", applicationId, code),
                Instant.now(), correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, etag(created.revision()))
                .header(HttpHeaders.LOCATION, "/api/v1/application-targets/" + created.id())
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(created));
    }

    @GetMapping("/application-targets/{targetId}")
    public ResponseEntity<ApplicationTargetResource> getTarget(
            @PathVariable UUID targetId,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativePermissions.APPLICATION_TARGET_READ,
                new AdministrativeResource("application-target", targetId),
                correlationId);
        ApplicationTarget target = queries.findTarget(actor.tenant(), targetId)
                .orElseThrow(() -> CatalogApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(target.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(target));
    }

    @PostMapping("/application-targets/{targetId}/retire")
    public ResponseEntity<ApplicationTargetResource> retireTarget(
            @PathVariable UUID targetId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        ApplicationTarget retired = mutations.retireTarget(
                actor, targetId, revision, key,
                fingerprint("target:retire", targetId, revision),
                Instant.now(), correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(retired.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(retired));
    }

    @GetMapping("/applications/{applicationId}/entitlements")
    public ResponseEntity<EntitlementPage> listEntitlements(
            @PathVariable UUID applicationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativePermissions.ENTITLEMENT_READ,
                AdministrativeResource.collection("entitlement"),
                correlationId);
        if (queries.findApplication(actor.tenant(), applicationId).isEmpty()) {
            throw CatalogApiException.notFound(correlationId);
        }
        int effectiveLimit = validateLimit(limit, correlationId);
        PagePosition position = decodeEntitlements(cursor, actor, applicationId, correlationId);
        var page = queries.listEntitlements(actor.tenant(), applicationId, position, effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .body(new EntitlementPage(
                        page.items().stream().map(CatalogController::resource).toList(),
                        cursors.encodeEntitlements(
                                actor.tenant(), applicationId, page.nextPosition())));
    }

    @PostMapping("/applications/{applicationId}/entitlements")
    public ResponseEntity<EntitlementResource> createEntitlement(
            @PathVariable UUID applicationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        Map<String,Object> parsed = allowed(
                body,
                Set.of("applicationTargetId","code","nativeKey","entitlementType"),
                Set.of("code","entitlementType"),
                correlationId);
        UUID targetId = optionalUuid(parsed.get("applicationTargetId"), "applicationTargetId", correlationId);
        String code = requireText(parsed, "code", 256, correlationId);
        String nativeKey = optionalText(parsed, "nativeKey", 1024, correlationId);
        String type = requireText(parsed, "entitlementType", 64, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Entitlement created = mutations.createEntitlement(
                actor, applicationId, targetId, code, nativeKey, type, key,
                fingerprint("entitlement:create", applicationId, targetId, code, nativeKey, type),
                Instant.now(), correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, etag(created.revision()))
                .header(HttpHeaders.LOCATION, "/api/v1/entitlements/" + created.id())
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(created));
    }

    @GetMapping("/entitlements/{entitlementId}")
    public ResponseEntity<EntitlementResource> getEntitlement(
            @PathVariable UUID entitlementId,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativePermissions.ENTITLEMENT_READ,
                new AdministrativeResource("entitlement", entitlementId),
                correlationId);
        Entitlement entitlement = queries.findEntitlement(actor.tenant(), entitlementId)
                .orElseThrow(() -> CatalogApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(entitlement.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(entitlement));
    }

    @PostMapping("/entitlements/{entitlementId}/retire")
    public ResponseEntity<EntitlementResource> retireEntitlement(
            @PathVariable UUID entitlementId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Entitlement retired = mutations.retireEntitlement(
                actor, entitlementId, revision, key,
                fingerprint("entitlement:retire", entitlementId, revision),
                Instant.now(), correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(retired.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(retired));
    }

    private void requireRead(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(actor, permission, resource, Instant.now()).allowed()) {
            throw CatalogApiException.forbidden(correlationId);
        }
    }

    private PagePosition decodeApplications(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeApplications(cursor, actor.tenant());
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private PagePosition decodeTargets(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID applicationId,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeTargets(cursor, actor.tenant(), applicationId);
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private PagePosition decodeEntitlements(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID applicationId,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeEntitlements(cursor, actor.tenant(), applicationId);
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private static CatalogApiException invalidCursor(UUID correlationId) {
        return CatalogApiException.validation(
                correlationId, "cursor", "invalid_cursor", "cursor is invalid or malformed.");
    }

    private static int validateLimit(Integer limit, UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > 200) {
            throw CatalogApiException.validation(
                    correlationId, "limit", "out_of_range",
                    "limit must be between 1 and 200.");
        }
        return value;
    }

    private static long parseIfMatch(String value, UUID correlationId) {
        if (value == null || !value.matches("\"rev-[1-9][0-9]*\"")) {
            throw CatalogApiException.validation(
                    correlationId, "If-Match", "invalid_revision_etag",
                    "If-Match must be a strong revision ETag such as \"rev-7\".");
        }
        try {
            return Long.parseLong(value.substring(5, value.length() - 1));
        } catch (NumberFormatException invalid) {
            throw CatalogApiException.validation(
                    correlationId, "If-Match", "invalid_revision_etag",
                    "If-Match revision is outside the supported range.");
        }
    }

    private static String validateIdempotencyKey(String key, UUID correlationId) {
        if (key == null || key.isBlank() || key.length() < 8 || key.length() > 200) {
            throw CatalogApiException.validation(
                    correlationId, "Idempotency-Key", "invalid_length",
                    "Idempotency-Key must contain between 8 and 200 characters.");
        }
        return key;
    }

    private static Map<String,Object> exact(
            Map<String,Object> body,
            Set<String> fields,
            UUID correlationId) {
        if (body == null) {
            throw CatalogApiException.validation(
                    correlationId, "request", "required_object",
                    "Request body must be a JSON object.");
        }
        if (!body.keySet().equals(fields)) {
            throw CatalogApiException.validation(
                    correlationId, "request", "unexpected_fields",
                    "Request body fields do not match the operation contract.");
        }
        return new LinkedHashMap<>(body);
    }

    private static Map<String,Object> allowed(
            Map<String,Object> body,
            Set<String> allowedFields,
            Set<String> requiredFields,
            UUID correlationId) {
        if (body == null
                || !allowedFields.containsAll(body.keySet())
                || !body.keySet().containsAll(requiredFields)) {
            throw CatalogApiException.validation(
                    correlationId, "request", "unexpected_fields",
                    "Request body fields do not match the operation contract.");
        }
        return new LinkedHashMap<>(body);
    }

    private static String requireText(
            Map<String,Object> body,
            String field,
            int max,
            UUID correlationId) {
        Object value = body.get(field);
        if (!(value instanceof String text)
                || text.isBlank()
                || text.trim().length() > max) {
            throw CatalogApiException.validation(
                    correlationId, field, "invalid_text",
                    field + " must be non-blank and at most " + max + " characters.");
        }
        return text.trim();
    }

    private static String optionalText(
            Map<String,Object> body,
            String field,
            int max,
            UUID correlationId) {
        Object value = body.get(field);
        if (value == null) return null;
        if (!(value instanceof String text)
                || text.isBlank()
                || text.trim().length() > max) {
            throw CatalogApiException.validation(
                    correlationId, field, "invalid_text",
                    field + " must be null or non-blank and at most " + max + " characters.");
        }
        return text.trim();
    }

    private static UUID optionalUuid(
            Object value,
            String field,
            UUID correlationId) {
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw CatalogApiException.validation(
                    correlationId, field, "invalid_uuid", field + " must be a UUID string or null.");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw CatalogApiException.validation(
                    correlationId, field, "invalid_uuid", field + " must be a UUID string or null.");
        }
    }

    private static RequestFingerprint fingerprint(Object... values) {
        StringBuilder normalized = new StringBuilder();
        for (Object value : values) {
            normalized.append(value == null ? "<null>" : value.toString()).append('\u0000');
        }
        return RequestFingerprint.sha256(
                normalized.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String etag(long revision) {
        return "\"rev-" + revision + "\"";
    }

    private static ApplicationResource resource(Application value) {
        return new ApplicationResource(
                value.id(), value.code(), value.name(), value.lifecycleState().name(),
                value.revision(), value.createdAt(), value.updatedAt());
    }

    private static ApplicationTargetResource resource(ApplicationTarget value) {
        return new ApplicationTargetResource(
                value.id(), value.applicationId(), value.code(), value.lifecycleState().name(),
                value.revision(), value.createdAt(), value.updatedAt());
    }

    private static EntitlementResource resource(Entitlement value) {
        return new EntitlementResource(
                value.id(), value.applicationId(), value.applicationTargetId(),
                value.code(), value.nativeKey(), value.entitlementType(),
                value.lifecycleState().name(), value.revision(),
                value.createdAt(), value.updatedAt());
    }
}
