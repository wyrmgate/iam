package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.identity.PrincipalApiModels.PrincipalPage;
import io.wyrmgate.iam.api.identity.PrincipalApiModels.PrincipalResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.identity.application.PrincipalQueryModels.PrincipalPosition;
import io.wyrmgate.iam.identity.application.PrincipalQueryService;
import io.wyrmgate.iam.identity.domain.Principal;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
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
@RequestMapping("/api/v1/principals")
public class PrincipalController {

    private static final int DEFAULT_LIMIT = 50;

    private final PrincipalQueryService queries;
    private final PrincipalApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final IdentityCursorCodec cursors;

    public PrincipalController(
            PrincipalQueryService queries,
            PrincipalApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            IdentityCursorCodec cursors) {
        this.queries = queries;
        this.mutations = mutations;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping
    public ResponseEntity<PrincipalPage> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                IdentityApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativeResource.collection("principal"),
                correlationId);
        int effectiveLimit = validateLimit(
                limit, correlationId);
        PrincipalPosition position = decodeCursor(
                cursor, actor, correlationId);
        var page = queries.list(
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
                .body(new PrincipalPage(
                        page.items().stream()
                                .map(PrincipalController::resource)
                                .toList(),
                        cursors.encodePrincipal(
                                actor.tenant(),
                                page.nextPosition())));
    }

    @PostMapping
    public ResponseEntity<PrincipalResource> register(
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId =
                IdentityApiRequestContext.resolveCorrelationId(
                        request, ids);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        requireExactFields(
                body,
                Set.of(
                        "applicationTargetId",
                        "nativePrincipalKey"),
                correlationId);
        UUID applicationTargetId = requiredUuid(
                body.get("applicationTargetId"),
                "applicationTargetId",
                correlationId);
        String nativePrincipalKey = requiredText(
                body.get("nativePrincipalKey"),
                "nativePrincipalKey",
                512,
                correlationId);

        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Principal created = mutations.register(
                actor,
                applicationTargetId,
                nativePrincipalKey,
                key,
                fingerprint(
                        "principal:register",
                        applicationTargetId,
                        nativePrincipalKey),
                Instant.now(),
                correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(
                        HttpHeaders.ETAG,
                        etag(created.revision()))
                .header(
                        HttpHeaders.LOCATION,
                        "/api/v1/principals/" + created.id())
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(created));
    }

    @GetMapping("/{principalId}")
    public ResponseEntity<PrincipalResource> get(
            @PathVariable UUID principalId,
            HttpServletRequest request) {
        UUID correlationId =
                IdentityApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                new AdministrativeResource(
                        "principal", principalId),
                correlationId);
        Principal principal = queries.findById(
                        actor.tenant(), principalId)
                .orElseThrow(() ->
                        IdentityApiException.notFound(
                                correlationId));
        return response(principal, correlationId);
    }

    @PostMapping("/{principalId}:correlate")
    public ResponseEntity<PrincipalResource> correlate(
            @PathVariable UUID principalId,
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
        UUID correlationId =
                IdentityApiRequestContext.resolveCorrelationId(
                        request, ids);
        long expectedRevision = parseIfMatch(
                ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        requireExactFields(
                body,
                Set.of("identityId"),
                correlationId);
        UUID identityId = requiredUuid(
                body.get("identityId"),
                "identityId",
                correlationId);

        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Principal updated = mutations.correlate(
                actor,
                principalId,
                identityId,
                expectedRevision,
                key,
                fingerprint(
                        "principal:correlate",
                        principalId,
                        identityId,
                        expectedRevision),
                Instant.now(),
                correlationId);
        return response(updated, correlationId);
    }

    private void requireRead(
            AuthenticatedAdministrativeActor actor,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(
                actor,
                AdministrativePermissions.PRINCIPAL_READ,
                resource,
                Instant.now()).allowed()) {
            throw IdentityApiException.forbidden(
                    correlationId);
        }
    }

    private PrincipalPosition decodeCursor(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodePrincipal(
                    cursor, actor.tenant());
        } catch (IllegalArgumentException invalid) {
            throw IdentityApiException.validation(
                    correlationId,
                    "cursor",
                    "invalid_cursor",
                    "cursor is invalid or malformed.");
        }
    }

    private static ResponseEntity<PrincipalResource> response(
            Principal principal,
            UUID correlationId) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(principal.revision()))
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store")
                .body(resource(principal));
    }

    private static PrincipalResource resource(
            Principal principal) {
        return new PrincipalResource(
                principal.id(),
                principal.identityId(),
                principal.applicationTargetId(),
                principal.kind().name(),
                principal.nativePrincipalKey(),
                principal.lifecycleState().name(),
                principal.revision(),
                principal.createdAt(),
                principal.updatedAt());
    }

    private static int validateLimit(
            Integer limit,
            UUID correlationId) {
        int value = limit == null
                ? DEFAULT_LIMIT
                : limit;
        if (value < 1 || value > 200) {
            throw IdentityApiException.validation(
                    correlationId,
                    "limit",
                    "out_of_range",
                    "limit must be between 1 and 200.");
        }
        return value;
    }

    private static void requireExactFields(
            Map<String,Object> body,
            Set<String> expected,
            UUID correlationId) {
        if (body == null) {
            throw IdentityApiException.validation(
                    correlationId,
                    "request",
                    "required_object",
                    "Request body must be a JSON object.");
        }
        Set<String> actual =
                new HashSet<>(body.keySet());
        for (String field : expected) {
            if (!actual.contains(field)) {
                throw IdentityApiException.validation(
                        correlationId,
                        field,
                        "required",
                        field + " is required.");
            }
        }
        actual.removeAll(expected);
        if (!actual.isEmpty()) {
            String field = actual.stream()
                    .sorted()
                    .findFirst()
                    .orElseThrow();
            throw IdentityApiException.validation(
                    correlationId,
                    field,
                    "unknown_field",
                    "Unknown field is not permitted.");
        }
    }

    private static UUID requiredUuid(
            Object value,
            String field,
            UUID correlationId) {
        if (!(value instanceof String text)) {
            throw IdentityApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string.");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw IdentityApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string.");
        }
    }

    private static String requiredText(
            Object value,
            String field,
            int maxLength,
            UUID correlationId) {
        if (!(value instanceof String text)
                || text.isBlank()
                || text.indexOf('\u0000') >= 0
                || text.trim().length() > maxLength) {
            throw IdentityApiException.validation(
                    correlationId,
                    field,
                    "invalid_value",
                    field + " must be non-blank and at most "
                            + maxLength + " characters.");
        }
        return text.trim();
    }

    private static long parseIfMatch(
            String value,
            UUID correlationId) {
        if (value == null
                || !value.matches(
                        "\"rev-[1-9][0-9]*\"")) {
            throw IdentityApiException.validation(
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
            throw IdentityApiException.validation(
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
            throw IdentityApiException.validation(
                    correlationId,
                    "Idempotency-Key",
                    "invalid_length",
                    "Idempotency-Key must contain between 8 and 200 characters.");
        }
        return key;
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
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String etag(long revision) {
        return "\"rev-" + revision + "\"";
    }
}
