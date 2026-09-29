package io.wyrmgate.iam.api.credential;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.credential.CredentialApiModels.CredentialPage;
import io.wyrmgate.iam.api.credential.CredentialApiModels.CredentialResource;
import io.wyrmgate.iam.api.credential.CredentialApiModels.CredentialRotationPage;
import io.wyrmgate.iam.api.credential.CredentialApiModels.CredentialRotationResource;
import io.wyrmgate.iam.api.credential.CredentialApiModels.SecretReferenceResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.credential.application.CredentialQueryModels.CredentialPosition;
import io.wyrmgate.iam.credential.application.CredentialQueryModels.RotationPosition;
import io.wyrmgate.iam.credential.application.CredentialQueryService;
import io.wyrmgate.iam.credential.domain.CredentialModels.Credential;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialKind;
import io.wyrmgate.iam.credential.domain.CredentialModels.CredentialRotation;
import io.wyrmgate.iam.credential.domain.CredentialModels.SecretReference;
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
public class CredentialController {

    private static final int DEFAULT_LIMIT = 50;

    private final CredentialQueryService queries;
    private final CredentialApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final CredentialCursorCodec cursors;

    public CredentialController(
            CredentialQueryService queries,
            CredentialApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            CredentialCursorCodec cursors) {
        this.queries = queries;
        this.mutations = mutations;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping("/credentials")
    public ResponseEntity<CredentialPage> listCredentials(
            @RequestParam UUID principalId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                CredentialApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        require(
                actor,
                AdministrativePermissions.CREDENTIAL_READ,
                AdministrativeResource.collection("credential"),
                correlationId);
        int effectiveLimit =
                validateLimit(limit, correlationId);
        CredentialPosition position =
                decodeCredentials(
                        cursor,
                        actor,
                        principalId,
                        correlationId);
        var page = queries.listCredentials(
                actor.tenant(),
                principalId,
                position,
                effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CredentialPage(
                        page.items().stream()
                                .map(CredentialController::resource)
                                .toList(),
                        cursors.encodeCredentials(
                                actor.tenant(),
                                principalId,
                                page.nextPosition())));
    }

    @PostMapping("/credentials")
    public ResponseEntity<CredentialResource> createCredential(
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId =
                CredentialApiRequestContext.resolveCorrelationId(
                        request, ids);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        Map<String,Object> parsed = exact(
                body,
                Set.of(
                        "principalId",
                        "kind",
                        "secretReference",
                        "validFrom",
                        "validUntil"),
                correlationId);
        UUID principalId = requiredUuid(
                parsed.get("principalId"),
                "principalId",
                correlationId);
        CredentialKind kind = credentialKind(
                parsed.get("kind"), correlationId);
        SecretReference secretReference =
                secretReference(
                        parsed.get("secretReference"),
                        correlationId);
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
        Credential created = mutations.create(
                actor,
                principalId,
                kind,
                secretReference,
                validFrom,
                validUntil,
                key,
                fingerprint(
                        "credential:create",
                        principalId,
                        kind,
                        secretReference.providerType(),
                        secretReference.referenceKey(),
                        validFrom,
                        validUntil),
                Instant.now(),
                correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, etag(created.revision()))
                .header(
                        HttpHeaders.LOCATION,
                        "/api/v1/credentials/" + created.id())
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(created));
    }

    @GetMapping("/credentials/{credentialId}")
    public ResponseEntity<CredentialResource> getCredential(
            @PathVariable UUID credentialId,
            HttpServletRequest request) {
        UUID correlationId =
                CredentialApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        require(
                actor,
                AdministrativePermissions.CREDENTIAL_READ,
                new AdministrativeResource(
                        "credential", credentialId),
                correlationId);
        Credential value = queries.findCredential(
                        actor.tenant(), credentialId)
                .orElseThrow(() ->
                        CredentialApiException.notFound(
                                correlationId));
        return credentialResponse(
                value, correlationId);
    }

    @PostMapping("/credentials/{credentialId}:revoke")
    public ResponseEntity<CredentialResource> revokeCredential(
            @PathVariable UUID credentialId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody(required = false)
                    Map<String,Object> body,
            HttpServletRequest request) {
        return lifecycle(
                credentialId,
                ifMatch,
                idempotencyKey,
                body,
                request,
                false);
    }

    @PostMapping("/credentials/{credentialId}:compromise")
    public ResponseEntity<CredentialResource> compromiseCredential(
            @PathVariable UUID credentialId,
            @RequestHeader(
                    name = "If-Match",
                    required = false)
                    String ifMatch,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody(required = false)
                    Map<String,Object> body,
            HttpServletRequest request) {
        return lifecycle(
                credentialId,
                ifMatch,
                idempotencyKey,
                body,
                request,
                true);
    }

    @PostMapping("/credentials/{credentialId}:rotate")
    public ResponseEntity<CredentialRotationResource> rotateCredential(
            @PathVariable UUID credentialId,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody(required = false)
                    Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId =
                CredentialApiRequestContext.resolveCorrelationId(
                        request, ids);
        validateEmptyBody(body, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        CredentialRotation created = mutations.rotate(
                actor,
                credentialId,
                key,
                fingerprint(
                        "credential:rotate",
                        credentialId),
                Instant.now(),
                correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, etag(created.revision()))
                .header(
                        HttpHeaders.LOCATION,
                        "/api/v1/credential-rotations/"
                                + created.id())
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(created));
    }

    @GetMapping("/credentials/{credentialId}/rotations")
    public ResponseEntity<CredentialRotationPage> listRotations(
            @PathVariable UUID credentialId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                CredentialApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        require(
                actor,
                AdministrativePermissions.CREDENTIAL_ROTATION_READ,
                AdministrativeResource.collection(
                        "credential-rotation"),
                correlationId);
        if (queries.findCredential(
                actor.tenant(), credentialId).isEmpty()) {
            throw CredentialApiException.notFound(correlationId);
        }
        int effectiveLimit =
                validateLimit(limit, correlationId);
        RotationPosition position =
                decodeRotations(
                        cursor,
                        actor,
                        credentialId,
                        correlationId);
        var page = queries.listRotations(
                actor.tenant(),
                credentialId,
                position,
                effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CredentialRotationPage(
                        page.items().stream()
                                .map(CredentialController::resource)
                                .toList(),
                        cursors.encodeRotations(
                                actor.tenant(),
                                credentialId,
                                page.nextPosition())));
    }

    @GetMapping("/credential-rotations/{rotationId}")
    public ResponseEntity<CredentialRotationResource> getRotation(
            @PathVariable UUID rotationId,
            HttpServletRequest request) {
        UUID correlationId =
                CredentialApiRequestContext.resolveCorrelationId(
                        request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        require(
                actor,
                AdministrativePermissions.CREDENTIAL_ROTATION_READ,
                new AdministrativeResource(
                        "credential-rotation", rotationId),
                correlationId);
        CredentialRotation value = queries.findRotation(
                        actor.tenant(), rotationId)
                .orElseThrow(() ->
                        CredentialApiException.notFound(
                                correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(value.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(value));
    }

    private ResponseEntity<CredentialResource> lifecycle(
            UUID credentialId,
            String ifMatch,
            String idempotencyKey,
            Map<String,Object> body,
            HttpServletRequest request,
            boolean compromise) {
        UUID correlationId =
                CredentialApiRequestContext.resolveCorrelationId(
                        request, ids);
        validateEmptyBody(body, correlationId);
        long revision = parseIfMatch(
                ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Credential value = compromise
                ? mutations.compromise(
                        actor,
                        credentialId,
                        revision,
                        key,
                        fingerprint(
                                "credential:compromise",
                                credentialId,
                                revision),
                        Instant.now(),
                        correlationId)
                : mutations.revoke(
                        actor,
                        credentialId,
                        revision,
                        key,
                        fingerprint(
                                "credential:revoke",
                                credentialId,
                                revision),
                        Instant.now(),
                        correlationId);
        return credentialResponse(value, correlationId);
    }

    private ResponseEntity<CredentialResource> credentialResponse(
            Credential value,
            UUID correlationId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(value.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(value));
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            io.wyrmgate.iam.administration.domain.AdministrativePermission permission,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(
                actor,
                permission,
                resource,
                Instant.now()).allowed()) {
            throw CredentialApiException.forbidden(
                    correlationId);
        }
    }

    private CredentialPosition decodeCredentials(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID principalId,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeCredentials(
                    cursor,
                    actor.tenant(),
                    principalId);
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private RotationPosition decodeRotations(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID credentialId,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeRotations(
                    cursor,
                    actor.tenant(),
                    credentialId);
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private static CredentialApiException invalidCursor(
            UUID correlationId) {
        return CredentialApiException.validation(
                correlationId,
                "cursor",
                "invalid_cursor",
                "cursor is invalid or malformed.");
    }

    private static int validateLimit(
            Integer limit,
            UUID correlationId) {
        int value = limit == null
                ? DEFAULT_LIMIT
                : limit;
        if (value < 1 || value > 200) {
            throw CredentialApiException.validation(
                    correlationId,
                    "limit",
                    "out_of_range",
                    "limit must be between 1 and 200.");
        }
        return value;
    }

    private static void validateEmptyBody(
            Map<String,Object> body,
            UUID correlationId) {
        if (body != null && !body.isEmpty()) {
            throw CredentialApiException.validation(
                    correlationId,
                    "request",
                    "unexpected_fields",
                    "This operation does not accept request fields.");
        }
    }

    private static Map<String,Object> exact(
            Map<String,Object> body,
            Set<String> fields,
            UUID correlationId) {
        if (body == null
                || !body.keySet().equals(fields)) {
            throw CredentialApiException.validation(
                    correlationId,
                    "request",
                    "unexpected_fields",
                    "Request body fields do not match the operation contract.");
        }
        return new LinkedHashMap<>(body);
    }

    private static SecretReference secretReference(
            Object value,
            UUID correlationId) {
        if (!(value instanceof Map<?,?> raw)
                || raw.size() != 2
                || !raw.containsKey("providerType")
                || !raw.containsKey("referenceKey")) {
            throw CredentialApiException.validation(
                    correlationId,
                    "secretReference",
                    "invalid_shape",
                    "secretReference requires providerType and referenceKey only.");
        }
        String providerType = requiredString(
                raw.get("providerType"),
                "secretReference.providerType",
                correlationId);
        String referenceKey = requiredString(
                raw.get("referenceKey"),
                "secretReference.referenceKey",
                correlationId);
        return new SecretReference(
                providerType, referenceKey);
    }

    private static CredentialKind credentialKind(
            Object value,
            UUID correlationId) {
        if (!(value instanceof String text)) {
            throw CredentialApiException.validation(
                    correlationId,
                    "kind",
                    "invalid_enum",
                    "kind must be a supported Credential kind.");
        }
        try {
            return CredentialKind.valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw CredentialApiException.validation(
                    correlationId,
                    "kind",
                    "invalid_enum",
                    "kind must be a supported Credential kind.");
        }
    }

    private static UUID requiredUuid(
            Object value,
            String field,
            UUID correlationId) {
        if (!(value instanceof String text)) {
            throw CredentialApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string.");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw CredentialApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string.");
        }
    }

    private static String requiredString(
            Object value,
            String field,
            UUID correlationId) {
        if (!(value instanceof String text)
                || text.isBlank()) {
            throw CredentialApiException.validation(
                    correlationId,
                    field,
                    "required",
                    field + " must be a non-blank string.");
        }
        return text.trim();
    }

    private static Instant optionalInstant(
            Object value,
            String field,
            UUID correlationId) {
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw CredentialApiException.validation(
                    correlationId,
                    field,
                    "invalid_datetime",
                    field + " must be an RFC3339 instant or null.");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            throw CredentialApiException.validation(
                    correlationId,
                    field,
                    "invalid_datetime",
                    field + " must be an RFC3339 instant or null.");
        }
    }

    private static long parseIfMatch(
            String value,
            UUID correlationId) {
        if (value == null
                || !value.matches(
                        "\"rev-[1-9][0-9]*\"")) {
            throw CredentialApiException.validation(
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
            throw CredentialApiException.validation(
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
            throw CredentialApiException.validation(
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

    private static CredentialResource resource(
            Credential value) {
        return new CredentialResource(
                value.id(),
                value.principalId(),
                value.kind().name(),
                new SecretReferenceResource(
                        value.secretReference().providerType(),
                        value.secretReference().referenceKey()),
                value.state().name(),
                value.validFrom(),
                value.validUntil(),
                value.revision(),
                value.createdAt(),
                value.updatedAt(),
                value.compromisedAt(),
                value.revokedAt(),
                value.expiredAt());
    }

    private static CredentialRotationResource resource(
            CredentialRotation value) {
        return new CredentialRotationResource(
                value.id(),
                value.oldCredentialId(),
                value.replacementCredentialId(),
                value.initiatorIdentityId(),
                value.state().name(),
                value.failureCode(),
                value.revision(),
                value.createdAt(),
                value.updatedAt(),
                value.completedAt());
    }
}
