package io.wyrmgate.iam.api.identity;

import com.fasterxml.jackson.databind.JsonNode;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.identity.IdentityApiModels.CanonicalAttributePage;
import io.wyrmgate.iam.api.identity.IdentityApiModels.CanonicalAttributeResource;
import io.wyrmgate.iam.api.identity.IdentityApiModels.IdentityPage;
import io.wyrmgate.iam.api.identity.IdentityApiModels.IdentityResource;
import io.wyrmgate.iam.api.identity.IdentityApiModels.ProfileResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.CanonicalAttributePagePosition;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityPagePosition;
import io.wyrmgate.iam.identity.application.IdentityQueryService;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityProfile;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
@RequestMapping("/api/v1/identities")
public class IdentityController {

    private static final int DEFAULT_LIMIT = 50;

    private final IdentityQueryService queries;
    private final IdentityApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;

    public IdentityController(
            IdentityQueryService queries,
            IdentityApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids) {
        this.queries = queries;
        this.mutations = mutations;
        this.authorization = authorization;
        this.ids = ids;
    }

    @GetMapping
    public ResponseEntity<IdentityPage> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        requireRead(actor, AdministrativeResource.collection("identity"), now, correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        IdentityPagePosition position = decodeIdentityCursor(cursor, correlationId);
        var page = queries.listIdentities(actor.tenant(), position, effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .body(new IdentityPage(
                        page.items().stream().map(IdentityController::resource).toList(),
                        IdentityCursorCodec.encodeIdentity(page.nextPosition())));
    }

    @PostMapping
    public ResponseEntity<IdentityResource> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.resolveCorrelationId(request, ids);
        String causalKey = validateIdempotencyKey(idempotencyKey, correlationId);
        CreateRequest parsed = parseCreate(body, correlationId);
        Instant now = Instant.now();
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(
                createFingerprint(parsed).getBytes(StandardCharsets.UTF_8));
        Identity created = mutations.create(
                actor,
                parsed.type(),
                profile(parsed.type()),
                parsed.lifecycleState(),
                parsed.displayName(),
                causalKey,
                fingerprint,
                now,
                correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, etag(created.revision()))
                .header(HttpHeaders.LOCATION, "/api/v1/identities/" + created.id())
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(created));
    }

    @GetMapping("/{identityId}")
    public ResponseEntity<IdentityResource> get(
            @PathVariable UUID identityId,
            HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(actor, new AdministrativeResource("identity", identityId), Instant.now(), correlationId);
        Identity identity = queries.findIdentity(actor.tenant(), identityId)
                .orElseThrow(() -> IdentityApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(identity.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(identity));
    }

    @PatchMapping("/{identityId}")
    public ResponseEntity<IdentityResource> updateMetadata(
            @PathVariable UUID identityId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.resolveCorrelationId(request, ids);
        long expectedRevision = parseIfMatch(ifMatch, correlationId);
        String causalKey = validateIdempotencyKey(idempotencyKey, correlationId);
        String displayName = parseUpdate(body, correlationId);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        RequestFingerprint fingerprint = RequestFingerprint.sha256(
                updateFingerprint(identityId, expectedRevision, displayName).getBytes(StandardCharsets.UTF_8));
        Identity updated = mutations.updateDisplayName(
                actor,
                identityId,
                displayName,
                expectedRevision,
                causalKey,
                fingerprint,
                now,
                correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(updated.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(updated));
    }

    @GetMapping("/{identityId}/canonical-attributes")
    public ResponseEntity<CanonicalAttributePage> canonicalAttributes(
            @PathVariable UUID identityId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        requireRead(actor, new AdministrativeResource("identity", identityId), now, correlationId);
        if (queries.findIdentity(actor.tenant(), identityId).isEmpty()) {
            throw IdentityApiException.notFound(correlationId);
        }
        int effectiveLimit = validateLimit(limit, correlationId);
        CanonicalAttributePagePosition position = decodeCanonicalCursor(cursor, correlationId);
        var page = queries.listCanonicalAttributes(actor.tenant(), identityId, position, effectiveLimit, now);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .body(new CanonicalAttributePage(
                        page.items().stream()
                                .map(item -> new CanonicalAttributeResource(
                                        item.definitionId(),
                                        item.definitionVersionId(),
                                        item.key(),
                                        item.classification(),
                                        item.type().name(),
                                        item.cardinality().name(),
                                        item.resolutionStatus().name(),
                                        item.valueRevision(),
                                        "REDACTED",
                                        item.hasTrustedValue()))
                                .toList(),
                        IdentityCursorCodec.encodeCanonical(page.nextPosition())));
    }

    private void requireRead(
            AuthenticatedAdministrativeActor actor,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(actor, AdministrativePermissions.IDENTITY_READ, resource, now).allowed()) {
            throw IdentityApiException.forbidden(correlationId);
        }
    }

    private static IdentityResource resource(Identity identity) {
        return new IdentityResource(
                identity.id(),
                identity.type().name(),
                new ProfileResource(identity.profile().identityType().name()),
                identity.lifecycleState().name(),
                identity.displayName(),
                identity.revision(),
                identity.createdAt(),
                identity.updatedAt());
    }

    private static IdentityProfile profile(IdentityType type) {
        return switch (type) {
            case PERSON -> new IdentityProfile.PersonProfile();
            case SERVICE -> new IdentityProfile.ServiceProfile();
            case WORKLOAD -> new IdentityProfile.WorkloadProfile();
        };
    }

    private static int validateLimit(Integer limit, UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > 200) {
            throw IdentityApiException.validation(
                    correlationId, "limit", "out_of_range", "limit must be between 1 and 200.");
        }
        return value;
    }

    private static String validateIdempotencyKey(String key, UUID correlationId) {
        if (key == null || key.isBlank() || key.length() < 8 || key.length() > 200) {
            throw IdentityApiException.validation(
                    correlationId,
                    "Idempotency-Key",
                    "invalid_length",
                    "Idempotency-Key must contain between 8 and 200 characters.");
        }
        return key;
    }

    private static long parseIfMatch(String value, UUID correlationId) {
        if (value == null || !value.matches("\\\"rev-[1-9][0-9]*\\\"")) {
            throw IdentityApiException.validation(
                    correlationId,
                    "If-Match",
                    "invalid_revision_etag",
                    "If-Match must be a strong revision ETag such as \"rev-7\".");
        }
        try {
            return Long.parseLong(value.substring(5, value.length() - 1));
        } catch (NumberFormatException invalid) {
            throw IdentityApiException.validation(
                    correlationId,
                    "If-Match",
                    "invalid_revision_etag",
                    "If-Match revision is outside the supported range.");
        }
    }

    private static IdentityPagePosition decodeIdentityCursor(String cursor, UUID correlationId) {
        if (cursor == null) return null;
        try {
            return IdentityCursorCodec.decodeIdentity(cursor);
        } catch (IllegalArgumentException invalid) {
            throw IdentityApiException.validation(
                    correlationId, "cursor", "invalid_cursor", "cursor is invalid or malformed.");
        }
    }

    private static CanonicalAttributePagePosition decodeCanonicalCursor(String cursor, UUID correlationId) {
        if (cursor == null) return null;
        try {
            return IdentityCursorCodec.decodeCanonical(cursor);
        } catch (IllegalArgumentException invalid) {
            throw IdentityApiException.validation(
                    correlationId, "cursor", "invalid_cursor", "cursor is invalid or malformed.");
        }
    }

    private static CreateRequest parseCreate(JsonNode body, UUID correlationId) {
        requireObject(body, correlationId);
        requireExactFields(body, Set.of("type", "profile", "lifecycleState", "displayName"), correlationId);
        IdentityType type = parseEnum(body, "type", IdentityType.class, correlationId);
        IdentityLifecycleState lifecycleState = parseEnum(
                body, "lifecycleState", IdentityLifecycleState.class, correlationId);
        String displayName = requireDisplayName(body, correlationId);
        JsonNode profile = body.get("profile");
        if (profile == null || !profile.isObject()) {
            throw IdentityApiException.validation(
                    correlationId, "profile", "required_object", "profile must be an object.");
        }
        requireExactFields(profile, Set.of("kind"), correlationId);
        String kind = requireText(profile, "kind", correlationId);
        if (!type.name().equals(kind)) {
            throw IdentityApiException.validation(
                    correlationId, "profile.kind", "type_mismatch", "profile.kind must equal type.");
        }
        return new CreateRequest(type, lifecycleState, displayName);
    }

    private static String parseUpdate(JsonNode body, UUID correlationId) {
        requireObject(body, correlationId);
        requireExactFields(body, Set.of("displayName"), correlationId);
        return requireDisplayName(body, correlationId);
    }

    private static void requireObject(JsonNode body, UUID correlationId) {
        if (body == null || !body.isObject()) {
            throw IdentityApiException.validation(
                    correlationId, "request", "required_object", "Request body must be a JSON object.");
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> expected, UUID correlationId) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        for (String required : expected) {
            if (!actual.contains(required)) {
                throw IdentityApiException.validation(
                        correlationId, required, "required", required + " is required.");
            }
        }
        actual.removeAll(expected);
        if (!actual.isEmpty()) {
            String field = actual.stream().sorted().findFirst().orElseThrow();
            throw IdentityApiException.validation(
                    correlationId, field, "unknown_field", "Unknown field is not permitted.");
        }
    }

    private static String requireDisplayName(JsonNode body, UUID correlationId) {
        String value = requireText(body, "displayName", correlationId);
        if (value.isBlank() || value.length() > 300 || value.indexOf('\u0000') >= 0) {
            throw IdentityApiException.validation(
                    correlationId,
                    "displayName",
                    "invalid_value",
                    "displayName must be non-blank, contain no NUL character, and be at most 300 characters.");
        }
        return value;
    }

    private static String requireText(JsonNode node, String field, UUID correlationId) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw IdentityApiException.validation(
                    correlationId, field, "required_string", field + " must be a string.");
        }
        return value.textValue();
    }

    private static <E extends Enum<E>> E parseEnum(
            JsonNode node,
            String field,
            Class<E> type,
            UUID correlationId) {
        String value = requireText(node, field, correlationId);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw IdentityApiException.validation(
                    correlationId, field, "invalid_enum", field + " contains an unsupported value.");
        }
    }

    private static String createFingerprint(CreateRequest request) {
        return "v1|" + part(request.type().name()) + part(request.type().name())
                + part(request.lifecycleState().name()) + part(request.displayName());
    }

    private static String updateFingerprint(UUID identityId, long expectedRevision, String displayName) {
        return "v1|" + part(identityId.toString()) + part(Long.toString(expectedRevision)) + part(displayName);
    }

    private static String part(String value) {
        return value.length() + ":" + value + "|";
    }

    private static String etag(long revision) {
        return "\"rev-" + revision + "\"";
    }

    private record CreateRequest(
            IdentityType type,
            IdentityLifecycleState lifecycleState,
            String displayName) {
    }
}
