package io.wyrmgate.iam.api.authentication;

import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AuthenticationAdministrativePermissions;
import io.wyrmgate.iam.api.authentication.AuthenticationApiModels.AuthenticationClientPage;
import io.wyrmgate.iam.api.authentication.AuthenticationApiModels.AuthenticationClientResource;
import io.wyrmgate.iam.api.authentication.AuthenticationApiModels.AuthenticationLoginBindingPage;
import io.wyrmgate.iam.api.authentication.AuthenticationApiModels.AuthenticationLoginBindingResource;
import io.wyrmgate.iam.api.authentication.AuthenticationApiModels.AuthenticationSessionPage;
import io.wyrmgate.iam.api.authentication.AuthenticationApiModels.AuthenticationSessionResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.authentication.application.AuthenticationRepository;
import io.wyrmgate.iam.authentication.application.AuthenticationService;
import io.wyrmgate.iam.authentication.domain.AuthenticationClient;
import io.wyrmgate.iam.authentication.domain.AuthenticationLoginBinding;
import io.wyrmgate.iam.authentication.domain.AuthenticationSession;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
@RequestMapping("/api/v1/authentication")
public class AuthenticationController {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final AuthenticationService authentication;
    private final AuthenticationRepository repository;
    private final AuthenticationApiMutationService mutations;
    private final AuthenticationCursorCodec cursors;
    private final IdGenerator ids;

    public AuthenticationController(
            AuthenticationService authentication,
            AuthenticationRepository repository,
            AuthenticationApiMutationService mutations,
            AuthenticationCursorCodec cursors,
            IdGenerator ids) {
        this.authentication = authentication;
        this.repository = repository;
        this.mutations = mutations;
        this.cursors = cursors;
        this.ids = ids;
    }

    @GetMapping("/clients")
    public ResponseEntity<AuthenticationClientPage> listClients(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        mutations.requireRead(actor, AuthenticationAdministrativePermissions.CLIENT_READ,
                AdministrativeResource.collection("authentication-client"), Instant.now(), correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        AuthenticationCursorCodec.Position position = decode(cursor, "authentication-client", actor, correlationId);
        List<AuthenticationClient> found = authentication.listClients(
                actor.tenant(), position == null ? null : position.createdAt(),
                position == null ? null : position.id(), effectiveLimit + 1);
        boolean hasMore = found.size() > effectiveLimit;
        List<AuthenticationClient> page = hasMore ? found.subList(0, effectiveLimit) : found;
        String next = hasMore && !page.isEmpty()
                ? cursors.encode("authentication-client", actor.tenant(), position(page.get(page.size() - 1)))
                : null;
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthenticationClientPage(page.stream().map(AuthenticationController::resource).toList(), next));
    }

    @PostMapping("/clients")
    public ResponseEntity<AuthenticationClientResource> createClient(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        Map<String,Object> parsed = exact(body,
                Set.of("displayName", "clientType", "redirectUris", "postLogoutRedirectUris", "scopes"),
                correlationId);
        String displayName = requireText(parsed, "displayName", 256, correlationId);
        AuthenticationClient.ClientType clientType = clientType(parsed.get("clientType"), correlationId);
        List<URI> redirects = uriList(parsed.get("redirectUris"), "redirectUris", true, correlationId);
        List<URI> logoutRedirects = uriList(
                parsed.get("postLogoutRedirectUris"), "postLogoutRedirectUris", false, correlationId);
        Set<String> scopes = textSet(parsed.get("scopes"), "scopes", true, 128, correlationId);
        if (clientType == AuthenticationClient.ClientType.CONFIDENTIAL) {
            throw AuthenticationApiException.validation(
                    correlationId, "clientType", "unsupported_client_type",
                    "CONFIDENTIAL clients require the client-credential secret boundary and are not enabled by this slice.");
        }
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        AuthenticationClient created = mutations.createClient(
                actor, displayName, clientType, redirects, logoutRedirects, scopes, key,
                fingerprint("client:create", displayName, clientType, redirects, logoutRedirects, scopes),
                Instant.now(), correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, etag(created.revision()))
                .header(HttpHeaders.LOCATION, "/api/v1/authentication/clients/" + created.id())
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(created));
    }

    @GetMapping("/clients/{id}")
    public ResponseEntity<AuthenticationClientResource> getClient(
            @PathVariable UUID id, HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        mutations.requireRead(actor, AuthenticationAdministrativePermissions.CLIENT_READ,
                new AdministrativeResource("authentication-client", id), Instant.now(), correlationId);
        AuthenticationClient value = repository.findClient(actor.tenant(), id)
                .orElseThrow(() -> AuthenticationApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(value.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(value));
    }

    @PatchMapping("/clients/{id}")
    public ResponseEntity<AuthenticationClientResource> updateClient(
            @PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        Map<String,Object> parsed = exact(body,
                Set.of("displayName", "redirectUris", "postLogoutRedirectUris", "scopes"), correlationId);
        String displayName = requireText(parsed, "displayName", 256, correlationId);
        List<URI> redirects = uriList(parsed.get("redirectUris"), "redirectUris", true, correlationId);
        List<URI> logoutRedirects = uriList(
                parsed.get("postLogoutRedirectUris"), "postLogoutRedirectUris", false, correlationId);
        Set<String> scopes = textSet(parsed.get("scopes"), "scopes", true, 128, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        AuthenticationClient updated = mutations.updateClient(
                actor, id, displayName, redirects, logoutRedirects, scopes, revision,
                Instant.now(), correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(updated.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(updated));
    }

    @PostMapping("/clients/{id}/disable")
    public ResponseEntity<AuthenticationClientResource> disableClient(
            @PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        AuthenticationClient disabled = mutations.disableClient(
                actor, id, revision, key, fingerprint("client:disable", id, revision),
                Instant.now(), correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(disabled.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(disabled));
    }

    @GetMapping("/login-bindings")
    public ResponseEntity<AuthenticationLoginBindingPage> listLoginBindings(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        mutations.requireRead(actor, AuthenticationAdministrativePermissions.LOGIN_BINDING_READ,
                AdministrativeResource.collection("authentication-login-binding"), Instant.now(), correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        AuthenticationCursorCodec.Position position = decode(
                cursor, "authentication-login-binding", actor, correlationId);
        List<AuthenticationLoginBinding> found = authentication.listLoginBindings(
                actor.tenant(), position == null ? null : position.createdAt(),
                position == null ? null : position.id(), effectiveLimit + 1);
        boolean hasMore = found.size() > effectiveLimit;
        List<AuthenticationLoginBinding> page = hasMore ? found.subList(0, effectiveLimit) : found;
        String next = hasMore && !page.isEmpty()
                ? cursors.encode("authentication-login-binding", actor.tenant(), position(page.get(page.size() - 1)))
                : null;
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthenticationLoginBindingPage(
                        page.stream().map(AuthenticationController::resource).toList(), next));
    }

    @PostMapping("/login-bindings")
    public ResponseEntity<AuthenticationLoginBindingResource> createLoginBinding(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        Map<String,Object> parsed = exact(body, Set.of("principalId", "loginIdentifier"), correlationId);
        UUID principalId = requireUuid(parsed.get("principalId"), "principalId", correlationId);
        String loginIdentifier = requireText(parsed, "loginIdentifier", 320, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        AuthenticationLoginBinding created = mutations.createLoginBinding(
                actor, principalId, loginIdentifier, key,
                fingerprint("login-binding:create", principalId, loginIdentifier), Instant.now(), correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, etag(created.revision()))
                .header(HttpHeaders.LOCATION, "/api/v1/authentication/login-bindings/" + created.id())
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(created));
    }

    @GetMapping("/login-bindings/{id}")
    public ResponseEntity<AuthenticationLoginBindingResource> getLoginBinding(
            @PathVariable UUID id, HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        mutations.requireRead(actor, AuthenticationAdministrativePermissions.LOGIN_BINDING_READ,
                new AdministrativeResource("authentication-login-binding", id), Instant.now(), correlationId);
        AuthenticationLoginBinding value = repository.findLoginBinding(actor.tenant(), id)
                .orElseThrow(() -> AuthenticationApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(value.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(value));
    }

    @PatchMapping("/login-bindings/{id}")
    public ResponseEntity<AuthenticationLoginBindingResource> updateLoginBinding(
            @PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String loginIdentifier = requireText(
                exact(body, Set.of("loginIdentifier"), correlationId),
                "loginIdentifier", 320, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        AuthenticationLoginBinding updated = mutations.updateLoginBinding(
                actor, id, loginIdentifier, revision, Instant.now(), correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(updated.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(updated));
    }

    @PostMapping("/login-bindings/{id}/disable")
    public ResponseEntity<AuthenticationLoginBindingResource> disableLoginBinding(
            @PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        AuthenticationLoginBinding disabled = mutations.disableLoginBinding(
                actor, id, revision, key, fingerprint("login-binding:disable", id, revision),
                Instant.now(), correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(disabled.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(disabled));
    }

    @GetMapping("/sessions")
    public ResponseEntity<AuthenticationSessionPage> listSessions(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        mutations.requireRead(actor, AuthenticationAdministrativePermissions.SESSION_READ,
                AdministrativeResource.collection("authentication-session"), Instant.now(), correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        AuthenticationCursorCodec.Position position = decode(
                cursor, "authentication-session", actor, correlationId);
        List<AuthenticationSession> found = authentication.listSessions(
                actor.tenant(), position == null ? null : position.createdAt(),
                position == null ? null : position.id(), effectiveLimit + 1);
        boolean hasMore = found.size() > effectiveLimit;
        List<AuthenticationSession> page = hasMore ? found.subList(0, effectiveLimit) : found;
        String next = hasMore && !page.isEmpty()
                ? cursors.encode("authentication-session", actor.tenant(), position(page.get(page.size() - 1)))
                : null;
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthenticationSessionPage(
                        page.stream().map(AuthenticationController::resource).toList(), next));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<AuthenticationSessionResource> getSession(
            @PathVariable UUID id, HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        mutations.requireRead(actor, AuthenticationAdministrativePermissions.SESSION_READ,
                new AdministrativeResource("authentication-session", id), Instant.now(), correlationId);
        AuthenticationSession value = repository.findSession(actor.tenant(), id)
                .orElseThrow(() -> AuthenticationApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(value.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(value));
    }

    @PostMapping("/sessions/{id}/revoke")
    public ResponseEntity<AuthenticationSessionResource> revokeSession(
            @PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId = AuthenticationApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        AuthenticationSession revoked = mutations.revokeSession(
                actor, id, revision, key, fingerprint("session:revoke", id, revision),
                Instant.now(), correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(revoked.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(revoked));
    }

    private AuthenticationCursorCodec.Position decode(
            String cursor, String kind, AuthenticatedAdministrativeActor actor, UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decode(cursor, kind, actor.tenant());
        } catch (IllegalArgumentException invalid) {
            throw AuthenticationApiException.validation(
                    correlationId, "cursor", "invalid_cursor", "cursor is invalid or malformed.");
        }
    }

    private static int validateLimit(Integer limit, UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > MAX_LIMIT) {
            throw AuthenticationApiException.validation(
                    correlationId, "limit", "out_of_range",
                    "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return value;
    }

    private static long parseIfMatch(String value, UUID correlationId) {
        if (value == null || !value.matches("\"rev-[1-9][0-9]*\"")) {
            throw AuthenticationApiException.validation(
                    correlationId, "If-Match", "invalid_revision_etag",
                    "If-Match must be a strong revision ETag such as \"rev-7\".");
        }
        try {
            return Long.parseLong(value.substring(5, value.length() - 1));
        } catch (NumberFormatException invalid) {
            throw AuthenticationApiException.validation(
                    correlationId, "If-Match", "invalid_revision_etag",
                    "If-Match revision is outside the supported range.");
        }
    }

    private static String validateIdempotencyKey(String key, UUID correlationId) {
        if (key == null || key.isBlank() || key.length() < 8 || key.length() > 200) {
            throw AuthenticationApiException.validation(
                    correlationId, "Idempotency-Key", "invalid_length",
                    "Idempotency-Key must contain between 8 and 200 characters.");
        }
        return key;
    }

    private static Map<String,Object> exact(
            Map<String,Object> body, Set<String> fields, UUID correlationId) {
        if (body == null || !body.keySet().equals(fields)) {
            throw AuthenticationApiException.validation(
                    correlationId, "request", "unexpected_fields",
                    "Request body fields do not match the operation contract.");
        }
        return new LinkedHashMap<>(body);
    }

    private static String requireText(
            Map<String,Object> body, String field, int max, UUID correlationId) {
        Object value = body.get(field);
        if (!(value instanceof String text) || text.isBlank() || text.trim().length() > max) {
            throw AuthenticationApiException.validation(
                    correlationId, field, "invalid_text",
                    field + " must be non-blank and at most " + max + " characters.");
        }
        return text.trim();
    }

    private static UUID requireUuid(Object value, String field, UUID correlationId) {
        if (!(value instanceof String text)) {
            throw AuthenticationApiException.validation(
                    correlationId, field, "invalid_uuid", field + " must be a UUID string.");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw AuthenticationApiException.validation(
                    correlationId, field, "invalid_uuid", field + " must be a UUID string.");
        }
    }

    private static AuthenticationClient.ClientType clientType(Object value, UUID correlationId) {
        if (!(value instanceof String text)) {
            throw AuthenticationApiException.validation(
                    correlationId, "clientType", "invalid_enum", "clientType must be PUBLIC or CONFIDENTIAL.");
        }
        try {
            return AuthenticationClient.ClientType.valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw AuthenticationApiException.validation(
                    correlationId, "clientType", "invalid_enum", "clientType must be PUBLIC or CONFIDENTIAL.");
        }
    }

    private static List<URI> uriList(
            Object value, String field, boolean required, UUID correlationId) {
        if (!(value instanceof List<?> values) || (required && values.isEmpty())) {
            throw AuthenticationApiException.validation(
                    correlationId, field, "invalid_list", field + " must be a JSON array of URI strings.");
        }
        List<URI> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw AuthenticationApiException.validation(
                        correlationId, field, "invalid_uri", field + " must contain only URI strings.");
            }
            try {
                result.add(URI.create(text));
            } catch (IllegalArgumentException invalid) {
                throw AuthenticationApiException.validation(
                        correlationId, field, "invalid_uri", field + " contains an invalid URI.");
            }
        }
        return List.copyOf(result);
    }

    private static Set<String> textSet(
            Object value, String field, boolean required, int max, UUID correlationId) {
        if (!(value instanceof List<?> values) || (required && values.isEmpty())) {
            throw AuthenticationApiException.validation(
                    correlationId, field, "invalid_list", field + " must be a JSON array of strings.");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank() || text.trim().length() > max) {
                throw AuthenticationApiException.validation(
                        correlationId, field, "invalid_text", field + " contains an invalid value.");
            }
            result.add(text.trim());
        }
        return Set.copyOf(result);
    }

    private static RequestFingerprint fingerprint(Object... values) {
        StringBuilder normalized = new StringBuilder();
        for (Object value : values) {
            normalized.append(value == null ? "<null>" : value.toString()).append('\u0000');
        }
        return RequestFingerprint.sha256(normalized.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String etag(long revision) {
        return "\"rev-" + revision + "\"";
    }

    private static AuthenticationCursorCodec.Position position(AuthenticationClient value) {
        return new AuthenticationCursorCodec.Position(value.createdAt(), value.id());
    }

    private static AuthenticationCursorCodec.Position position(AuthenticationLoginBinding value) {
        return new AuthenticationCursorCodec.Position(value.createdAt(), value.id());
    }

    private static AuthenticationCursorCodec.Position position(AuthenticationSession value) {
        return new AuthenticationCursorCodec.Position(value.createdAt(), value.id());
    }

    private static AuthenticationClientResource resource(AuthenticationClient value) {
        return new AuthenticationClientResource(
                value.id(), value.clientId(), value.displayName(), value.clientType().name(),
                value.redirectUris(), value.postLogoutRedirectUris(), value.scopes(),
                value.lifecycleState().name(), value.revision(), value.createdAt(), value.updatedAt());
    }

    private static AuthenticationLoginBindingResource resource(AuthenticationLoginBinding value) {
        return new AuthenticationLoginBindingResource(
                value.id(), value.loginIdentifier(), value.principalId(), value.identityId(),
                value.lifecycleState().name(), value.revision(), value.createdAt(), value.updatedAt());
    }

    private static AuthenticationSessionResource resource(AuthenticationSession value) {
        return new AuthenticationSessionResource(
                value.id(), value.identityId(), value.principalId(), value.assurance().name(),
                value.lifecycleState().name(), value.authenticatedAt(), value.lastSeenAt(), value.expiresAt(),
                value.revokedAt(), value.revision(), value.createdAt(), value.updatedAt());
    }
}