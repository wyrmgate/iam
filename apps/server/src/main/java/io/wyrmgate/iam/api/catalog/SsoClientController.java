package io.wyrmgate.iam.api.catalog;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.application.CatalogRepository;
import io.wyrmgate.iam.catalog.application.SsoClientRegistrationService;
import io.wyrmgate.iam.catalog.domain.SsoClientRegistration;
import io.wyrmgate.iam.catalog.domain.SsoClientScope;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SsoClientController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final SsoClientRegistrationService service;
    private final CatalogRepository catalog;
    private final SsoClientApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final CatalogCursorCodec cursors;

    public SsoClientController(
            SsoClientRegistrationService service,
            CatalogRepository catalog,
            SsoClientApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            CatalogCursorCodec cursors) {
        this.service = service;
        this.catalog = catalog;
        this.mutations = mutations;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping("/applications/{applicationId}/sso-clients")
    public ResponseEntity<SsoClientPage> list(
            @PathVariable UUID applicationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.SSO_CLIENT_READ,
                AdministrativeResource.collection("sso-client"), correlationId);
        if (catalog.findApplication(actor.tenant(), applicationId).isEmpty()) {
            throw CatalogApiException.notFound(correlationId);
        }
        int effectiveLimit = validateLimit(limit, correlationId);
        PagePosition after = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                after = cursors.decodeSsoClients(cursor, actor.tenant(), applicationId);
            } catch (IllegalArgumentException invalid) {
                throw CatalogApiException.validation(
                        correlationId, "cursor", "invalid", "The cursor is invalid or expired.");
            }
        }
        var page = service.list(actor.tenant(), applicationId, after, effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .body(new SsoClientPage(
                        page.items().stream().map(SsoClientController::resource).toList(),
                        cursors.encodeSsoClients(actor.tenant(), applicationId, page.nextPosition())));
    }

    @PostMapping("/applications/{applicationId}/sso-clients")
    public ResponseEntity<SsoClientResource> create(
            @PathVariable UUID applicationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        String key = requireIdempotencyKey(idempotencyKey, correlationId);
        Map<String, Object> parsed = exactAllowed(body,
                Set.of("redirectUris", "allowedScopes", "requiresGovernedAccess"),
                Set.of("redirectUris"), correlationId);
        Set<String> redirectUris = stringSet(parsed.get("redirectUris"), "redirectUris", 20, 2048, correlationId);
        Set<SsoClientScope> scopes = parsed.containsKey("allowedScopes")
                ? scopes(parsed.get("allowedScopes"), correlationId)
                : Set.of(SsoClientScope.OPENID);
        boolean requiresGovernedAccess = parsed.containsKey("requiresGovernedAccess")
                ? bool(parsed.get("requiresGovernedAccess"), "requiresGovernedAccess", correlationId)
                : true;
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        SsoClientRegistration created = mutations.create(
                actor, applicationId, redirectUris, scopes, requiresGovernedAccess,
                key, fingerprint("create", applicationId, redirectUris, scopes, requiresGovernedAccess),
                Instant.now(), correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, etag(created.revision()))
                .header(HttpHeaders.LOCATION, "/api/v1/sso-clients/" + created.id())
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(created));
    }

    @GetMapping("/sso-clients/{registrationId}")
    public ResponseEntity<SsoClientResource> get(
            @PathVariable UUID registrationId,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.SSO_CLIENT_READ,
                new AdministrativeResource("sso-client", registrationId), correlationId);
        SsoClientRegistration registration = service.find(actor.tenant(), registrationId)
                .orElseThrow(() -> CatalogApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(registration.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(registration));
    }

    @PutMapping("/sso-clients/{registrationId}")
    public ResponseEntity<SsoClientResource> replace(
            @PathVariable UUID registrationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = requireIdempotencyKey(idempotencyKey, correlationId);
        Map<String, Object> parsed = exactAllowed(body,
                Set.of("redirectUris", "allowedScopes", "requiresGovernedAccess"),
                Set.of("redirectUris", "allowedScopes", "requiresGovernedAccess"), correlationId);
        Set<String> redirectUris = stringSet(parsed.get("redirectUris"), "redirectUris", 20, 2048, correlationId);
        Set<SsoClientScope> scopes = scopes(parsed.get("allowedScopes"), correlationId);
        boolean requiresGovernedAccess = bool(
                parsed.get("requiresGovernedAccess"), "requiresGovernedAccess", correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        SsoClientRegistration updated = mutations.replace(
                actor, registrationId, redirectUris, scopes, requiresGovernedAccess,
                revision, key,
                fingerprint("update", registrationId, revision, redirectUris, scopes, requiresGovernedAccess),
                Instant.now(), correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(updated.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(updated));
    }

    @PostMapping("/sso-clients/{registrationId}/retire")
    public ResponseEntity<SsoClientResource> retire(
            @PathVariable UUID registrationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = requireIdempotencyKey(idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        SsoClientRegistration retired = mutations.retire(
                actor, registrationId, revision, key,
                RequestFingerprint.sha256(("retire\u0000" + registrationId + "\u0000" + revision)
                        .getBytes(StandardCharsets.UTF_8)),
                Instant.now(), correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(retired.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(retired));
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(actor, permission, resource, Instant.now()).allowed()) {
            throw CatalogApiException.forbidden(correlationId);
        }
    }

    private static int validateLimit(Integer requested, UUID correlationId) {
        int value = requested == null ? DEFAULT_LIMIT : requested;
        if (value < 1 || value > MAX_LIMIT) {
            throw CatalogApiException.validation(
                    correlationId, "limit", "range", "limit must be between 1 and 200.");
        }
        return value;
    }

    private static String requireIdempotencyKey(String value, UUID correlationId) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw CatalogApiException.validation(
                    correlationId, "Idempotency-Key", "required",
                    "A non-blank Idempotency-Key of at most 200 characters is required.");
        }
        return value.trim();
    }

    private static long parseIfMatch(String value, UUID correlationId) {
        if (value == null || value.isBlank()) {
            throw CatalogApiException.validation(
                    correlationId, "If-Match", "required", "If-Match is required.");
        }
        String normalized = value.trim();
        if (normalized.startsWith("W/")) normalized = normalized.substring(2);
        if (normalized.length() < 3 || normalized.charAt(0) != '"'
                || normalized.charAt(normalized.length() - 1) != '"') {
            throw CatalogApiException.validation(
                    correlationId, "If-Match", "format", "If-Match must contain a quoted revision.");
        }
        try {
            long revision = Long.parseLong(normalized.substring(1, normalized.length() - 1));
            if (revision < 1) throw new NumberFormatException();
            return revision;
        } catch (NumberFormatException invalid) {
            throw CatalogApiException.validation(
                    correlationId, "If-Match", "format", "If-Match revision is invalid.");
        }
    }

    private static Map<String, Object> exactAllowed(
            Map<String, Object> body,
            Set<String> allowed,
            Set<String> required,
            UUID correlationId) {
        if (body == null) throw CatalogApiException.validation(
                correlationId, "body", "required", "Request body is required.");
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) throw CatalogApiException.validation(
                    correlationId, key, "unknown", "Unknown request field.");
        }
        for (String key : required) {
            if (!body.containsKey(key)) throw CatalogApiException.validation(
                    correlationId, key, "required", key + " is required.");
        }
        return body;
    }

    private static Set<String> stringSet(
            Object value, String field, int maxItems, int maxLength, UUID correlationId) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > maxItems) {
            throw CatalogApiException.validation(
                    correlationId, field, "size", field + " must contain between 1 and " + maxItems + " values.");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank() || text.length() > maxLength) {
                throw CatalogApiException.validation(
                        correlationId, field, "invalid", field + " contains an invalid value.");
            }
            result.add(text.trim());
        }
        if (result.size() != list.size()) throw CatalogApiException.validation(
                correlationId, field, "duplicate", field + " must not contain duplicate values.");
        return Set.copyOf(result);
    }

    private static Set<SsoClientScope> scopes(Object value, UUID correlationId) {
        Set<String> values = stringSet(value, "allowedScopes", 3, 32, correlationId);
        LinkedHashSet<SsoClientScope> result = new LinkedHashSet<>();
        for (String item : values) {
            try {
                result.add(SsoClientScope.fromProtocolValue(item));
            } catch (IllegalArgumentException invalid) {
                throw CatalogApiException.validation(
                        correlationId, "allowedScopes", "unsupported",
                        "Only openid, profile and email are supported in this protocol baseline.");
            }
        }
        if (!result.contains(SsoClientScope.OPENID)) throw CatalogApiException.validation(
                correlationId, "allowedScopes", "openid_required", "The openid scope is required.");
        return Set.copyOf(result);
    }

    private static boolean bool(Object value, String field, UUID correlationId) {
        if (!(value instanceof Boolean result)) throw CatalogApiException.validation(
                correlationId, field, "type", field + " must be boolean.");
        return result;
    }

    private static RequestFingerprint fingerprint(
            String action,
            Object id,
            Object... values) {
        List<String> parts = new ArrayList<>();
        parts.add(action);
        parts.add(String.valueOf(id));
        for (Object value : values) {
            if (value instanceof Set<?> set) {
                parts.add(set.stream().map(String::valueOf).sorted().reduce((a, b) -> a + "," + b).orElse(""));
            } else {
                parts.add(String.valueOf(value));
            }
        }
        return RequestFingerprint.sha256(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8));
    }

    private static String etag(long revision) {
        return "\"" + revision + "\"";
    }

    private static SsoClientResource resource(SsoClientRegistration registration) {
        return new SsoClientResource(
                registration.id(),
                registration.applicationId(),
                registration.clientId(),
                "PUBLIC",
                true,
                registration.redirectUris().stream().sorted().toList(),
                registration.allowedScopes().stream().map(SsoClientScope::protocolValue).sorted().toList(),
                registration.requiresGovernedAccess(),
                registration.lifecycleState().name(),
                registration.revision(),
                registration.createdAt(),
                registration.updatedAt());
    }

    public record SsoClientResource(
            UUID id,
            UUID applicationId,
            String clientId,
            String clientType,
            boolean pkceS256Required,
            List<String> redirectUris,
            List<String> allowedScopes,
            boolean requiresGovernedAccess,
            String lifecycleState,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    public record SsoClientPage(List<SsoClientResource> items, String nextCursor) {
        public SsoClientPage {
            items = List.copyOf(items);
        }
    }
}
