package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.identity.IdentityApiModels.CanonicalAttributePage;
import io.wyrmgate.iam.api.identity.IdentityApiModels.CanonicalAttributeResource;
import io.wyrmgate.iam.api.identity.IdentityApiModels.IdentityMergeOperationResource;
import io.wyrmgate.iam.api.identity.IdentityApiModels.IdentityPage;
import io.wyrmgate.iam.api.identity.IdentityApiModels.IdentityResource;
import io.wyrmgate.iam.api.identity.IdentityApiModels.IdentitySplitOperationResource;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
@RequestMapping("/api/v1/identities")
public class IdentityController {

    private static final int DEFAULT_LIMIT = 50;

    private final IdentityQueryService queries;
    private final IdentityApiMutationService mutations;
    private final IdentityMergeSplitApiMutationService mergeSplitMutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final IdentityCursorCodec cursors;

    public IdentityController(
            IdentityQueryService queries,
            IdentityApiMutationService mutations,
            IdentityMergeSplitApiMutationService mergeSplitMutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            IdentityCursorCodec cursors) {
        this.queries = queries;
        this.mutations = mutations;
        this.mergeSplitMutations = mergeSplitMutations;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
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
        IdentityPagePosition position = decodeIdentityCursor(cursor, actor, correlationId);
        var page = queries.listIdentities(actor.tenant(), position, effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .body(new IdentityPage(
                        page.items().stream().map(IdentityController::resource).toList(),
                        cursors.encodeIdentity(actor.tenant(), page.nextPosition())));
    }

    @PostMapping
    public ResponseEntity<IdentityResource> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body,
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
            @RequestBody Map<String, Object> body,
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


    @PostMapping("/{identityId}:merge")
    public ResponseEntity<IdentityMergeOperationResource> merge(
            @PathVariable UUID identityId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.resolveCorrelationId(request, ids);
        long survivorRevision = parseIfMatch(ifMatch, correlationId);
        String causalKey = validateIdempotencyKey(idempotencyKey, correlationId);
        MergeRequest parsed = parseMerge(body, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        RequestFingerprint fingerprint = RequestFingerprint.sha256(
                mergeFingerprint(
                                identityId,
                                survivorRevision,
                                parsed.absorbedIdentityId(),
                                parsed.absorbedRevision(),
                                parsed.reason())
                        .getBytes(StandardCharsets.UTF_8));
        var operation = mergeSplitMutations.merge(
                actor,
                identityId,
                survivorRevision,
                parsed.absorbedIdentityId(),
                parsed.absorbedRevision(),
                parsed.reason(),
                causalKey,
                fingerprint,
                now,
                correlationId);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new IdentityMergeOperationResource(
                        operation.id(),
                        operation.survivorIdentityId(),
                        operation.absorbedIdentityId(),
                        operation.movedLinkCount(),
                        operation.movedPrincipalCount(),
                        operation.completedAt()));
    }

    @PostMapping("/{identityId}:split")
    public ResponseEntity<IdentitySplitOperationResource> split(
            @PathVariable UUID identityId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.resolveCorrelationId(request, ids);
        long sourceRevision = parseIfMatch(ifMatch, correlationId);
        String causalKey = validateIdempotencyKey(idempotencyKey, correlationId);
        SplitRequest parsed = parseSplit(body, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        RequestFingerprint fingerprint = RequestFingerprint.sha256(
                splitFingerprint(
                                identityId,
                                sourceRevision,
                                parsed.newDisplayName(),
                                parsed.sourceRecordIds(),
                                parsed.principalIds(),
                                parsed.reason())
                        .getBytes(StandardCharsets.UTF_8));
        var operation = mergeSplitMutations.split(
                actor,
                identityId,
                sourceRevision,
                parsed.newDisplayName(),
                parsed.sourceRecordIds(),
                parsed.principalIds(),
                parsed.reason(),
                causalKey,
                fingerprint,
                now,
                correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.LOCATION, "/api/v1/identities/" + operation.newIdentityId())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new IdentitySplitOperationResource(
                        operation.id(),
                        operation.sourceIdentityId(),
                        operation.newIdentityId(),
                        operation.movedSourceRecordIds(),
                        operation.movedPrincipalIds(),
                        operation.completedAt()));
    }

    @PostMapping("/{identityId}:activate")
    public ResponseEntity<IdentityResource> activate(
            @PathVariable UUID identityId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        return lifecycle(
                identityId,
                IdentityLifecycleState.ACTIVE,
                ifMatch,
                idempotencyKey,
                body,
                request);
    }

    @PostMapping("/{identityId}:suspend")
    public ResponseEntity<IdentityResource> suspend(
            @PathVariable UUID identityId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        return lifecycle(
                identityId,
                IdentityLifecycleState.SUSPENDED,
                ifMatch,
                idempotencyKey,
                body,
                request);
    }

    @PostMapping("/{identityId}:deactivate")
    public ResponseEntity<IdentityResource> deactivate(
            @PathVariable UUID identityId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        return lifecycle(
                identityId,
                IdentityLifecycleState.INACTIVE,
                ifMatch,
                idempotencyKey,
                body,
                request);
    }

    @PostMapping("/{identityId}:decommission")
    public ResponseEntity<IdentityResource> decommission(
            @PathVariable UUID identityId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        return lifecycle(
                identityId,
                IdentityLifecycleState.DECOMMISSIONED,
                ifMatch,
                idempotencyKey,
                body,
                request);
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
        CanonicalAttributePagePosition position = decodeCanonicalCursor(cursor, actor, identityId, correlationId);
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
                        cursors.encodeCanonical(actor.tenant(), identityId, page.nextPosition())));
    }


    private ResponseEntity<IdentityResource> lifecycle(
            UUID identityId,
            IdentityLifecycleState targetState,
            String ifMatch,
            String idempotencyKey,
            Map<String, Object> body,
            HttpServletRequest request) {
        UUID correlationId =
                IdentityApiRequestContext.resolveCorrelationId(request, ids);
        long expectedRevision = parseIfMatch(ifMatch, correlationId);
        String causalKey = validateIdempotencyKey(idempotencyKey, correlationId);
        validateEmptyOperationBody(body, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        RequestFingerprint fingerprint = RequestFingerprint.sha256(
                lifecycleFingerprint(
                                identityId,
                                targetState,
                                expectedRevision)
                        .getBytes(StandardCharsets.UTF_8));
        Identity updated = mutations.changeLifecycle(
                actor,
                identityId,
                targetState,
                expectedRevision,
                causalKey,
                fingerprint,
                now,
                correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(updated.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(updated));
    }

    private static void validateEmptyOperationBody(
            Map<String, Object> body,
            UUID correlationId) {
        if (body != null && !body.isEmpty()) {
            throw IdentityApiException.validation(
                    correlationId,
                    "request",
                    "unexpected_fields",
                    "This lifecycle operation does not accept request fields.");
        }
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

    private IdentityPagePosition decodeIdentityCursor(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeIdentity(cursor, actor.tenant());
        } catch (IllegalArgumentException invalid) {
            throw IdentityApiException.validation(
                    correlationId, "cursor", "invalid_cursor", "cursor is invalid or malformed.");
        }
    }

    private CanonicalAttributePagePosition decodeCanonicalCursor(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID identityId,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeCanonical(cursor, actor.tenant(), identityId);
        } catch (IllegalArgumentException invalid) {
            throw IdentityApiException.validation(
                    correlationId, "cursor", "invalid_cursor", "cursor is invalid or malformed.");
        }
    }

    private static CreateRequest parseCreate(Map<String, Object> body, UUID correlationId) {
        requireObject(body, correlationId);
        requireExactFields(body, Set.of("type", "profile", "lifecycleState", "displayName"), correlationId);
        IdentityType type = parseEnum(body, "type", IdentityType.class, correlationId);
        IdentityLifecycleState lifecycleState = parseEnum(
                body, "lifecycleState", IdentityLifecycleState.class, correlationId);
        String displayName = requireDisplayName(body, correlationId);
        Object rawProfile = body.get("profile");
        if (!(rawProfile instanceof Map<?, ?> profileValues)) {
            throw IdentityApiException.validation(
                    correlationId, "profile", "required_object", "profile must be an object.");
        }
        Map<String, Object> profile = stringKeyedMap(profileValues, "profile", correlationId);
        requireExactFields(profile, Set.of("kind"), correlationId);
        String kind = requireText(profile, "kind", correlationId);
        if (!type.name().equals(kind)) {
            throw IdentityApiException.validation(
                    correlationId, "profile.kind", "type_mismatch", "profile.kind must equal type.");
        }
        return new CreateRequest(type, lifecycleState, displayName);
    }

    private static MergeRequest parseMerge(
            Map<String, Object> body,
            UUID correlationId) {
        requireObject(body, correlationId);
        requireExactFields(
                body,
                Set.of("absorbedIdentityId", "absorbedRevision", "reason"),
                correlationId);
        return new MergeRequest(
                requireUuid(body, "absorbedIdentityId", correlationId),
                requirePositiveLong(body, "absorbedRevision", correlationId),
                requireReason(body, "reason", correlationId));
    }

    private static SplitRequest parseSplit(
            Map<String, Object> body,
            UUID correlationId) {
        requireObject(body, correlationId);
        requireExactFields(
                body,
                Set.of(
                        "newDisplayName",
                        "sourceRecordIds",
                        "principalIds",
                        "reason"),
                correlationId);
        List<UUID> sourceRecordIds =
                requireUuidList(body, "sourceRecordIds", correlationId);
        List<UUID> principalIds =
                requireUuidList(body, "principalIds", correlationId);
        if (sourceRecordIds.isEmpty() && principalIds.isEmpty()) {
            throw IdentityApiException.validation(
                    correlationId,
                    "sourceRecordIds",
                    "relationship_required",
                    "split must move at least one SourceRecord link or Principal.");
        }
        return new SplitRequest(
                requireDisplayName(
                        Map.of("displayName", body.get("newDisplayName")),
                        correlationId),
                sourceRecordIds,
                principalIds,
                requireReason(body, "reason", correlationId));
    }

    private static UUID requireUuid(
            Map<String, Object> body,
            String field,
            UUID correlationId) {
        String text = requireText(body, field, correlationId);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw IdentityApiException.validation(
                    correlationId, field, "invalid_uuid", field + " must be a UUID.");
        }
    }

    private static long requirePositiveLong(
            Map<String, Object> body,
            String field,
            UUID correlationId) {
        Object raw = body.get(field);
        if (!(raw instanceof Number number)) {
            throw IdentityApiException.validation(
                    correlationId, field, "required_integer", field + " must be an integer.");
        }
        long value = number.longValue();
        if (value < 1) {
            throw IdentityApiException.validation(
                    correlationId, field, "out_of_range", field + " must be positive.");
        }
        return value;
    }

    private static List<UUID> requireUuidList(
            Map<String, Object> body,
            String field,
            UUID correlationId) {
        Object raw = body.get(field);
        if (!(raw instanceof List<?> list)) {
            throw IdentityApiException.validation(
                    correlationId, field, "required_array", field + " must be an array.");
        }
        if (list.size() > 200) {
            throw IdentityApiException.validation(
                    correlationId, field, "out_of_range", field + " supports at most 200 values.");
        }
        ArrayList<UUID> values = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw IdentityApiException.validation(
                        correlationId, field, "invalid_uuid", field + " must contain UUID strings.");
            }
            try {
                values.add(UUID.fromString(text));
            } catch (IllegalArgumentException invalid) {
                throw IdentityApiException.validation(
                        correlationId, field, "invalid_uuid", field + " must contain UUID strings.");
            }
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw IdentityApiException.validation(
                    correlationId, field, "duplicate_value", field + " must not contain duplicates.");
        }
        values.sort(Comparator.comparing(UUID::toString));
        return List.copyOf(values);
    }

    private static String requireReason(
            Map<String, Object> body,
            String field,
            UUID correlationId) {
        String reason = requireText(body, field, correlationId).trim();
        if (reason.isBlank() || reason.length() > 1024) {
            throw IdentityApiException.validation(
                    correlationId,
                    field,
                    "invalid_value",
                    field + " must be non-blank and at most 1024 characters.");
        }
        return reason;
    }

    private static String parseUpdate(Map<String, Object> body, UUID correlationId) {
        requireObject(body, correlationId);
        requireExactFields(body, Set.of("displayName"), correlationId);
        return requireDisplayName(body, correlationId);
    }

    private static void requireObject(Map<String, Object> body, UUID correlationId) {
        if (body == null) {
            throw IdentityApiException.validation(
                    correlationId, "request", "required_object", "Request body must be a JSON object.");
        }
    }

    private static Map<String, Object> stringKeyedMap(
            Map<?, ?> raw,
            String field,
            UUID correlationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw IdentityApiException.validation(
                        correlationId, field, "invalid_object", field + " must use string property names.");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void requireExactFields(
            Map<String, Object> node,
            Set<String> expected,
            UUID correlationId) {
        Set<String> actual = new HashSet<>(node.keySet());
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

    private static String requireDisplayName(Map<String, Object> body, UUID correlationId) {
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

    private static String requireText(Map<String, Object> node, String field, UUID correlationId) {
        Object value = node.get(field);
        if (!(value instanceof String text)) {
            throw IdentityApiException.validation(
                    correlationId, field, "required_string", field + " must be a string.");
        }
        return text;
    }

    private static <E extends Enum<E>> E parseEnum(
            Map<String, Object> node,
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

    private static String mergeFingerprint(
            UUID survivorIdentityId,
            long survivorRevision,
            UUID absorbedIdentityId,
            long absorbedRevision,
            String reason) {
        return "v1|"
                + part(survivorIdentityId.toString())
                + part(Long.toString(survivorRevision))
                + part(absorbedIdentityId.toString())
                + part(Long.toString(absorbedRevision))
                + part(reason);
    }

    private static String splitFingerprint(
            UUID sourceIdentityId,
            long sourceRevision,
            String newDisplayName,
            List<UUID> sourceRecordIds,
            List<UUID> principalIds,
            String reason) {
        return "v1|"
                + part(sourceIdentityId.toString())
                + part(Long.toString(sourceRevision))
                + part(newDisplayName)
                + part(sourceRecordIds.stream().map(UUID::toString)
                        .collect(java.util.stream.Collectors.joining(",")))
                + part(principalIds.stream().map(UUID::toString)
                        .collect(java.util.stream.Collectors.joining(",")))
                + part(reason);
    }

    private static String lifecycleFingerprint(
            UUID identityId,
            IdentityLifecycleState targetState,
            long expectedRevision) {
        return "v1|"
                + part(identityId.toString())
                + part(targetState.name())
                + part(Long.toString(expectedRevision));
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

    private record MergeRequest(
            UUID absorbedIdentityId,
            long absorbedRevision,
            String reason) {
    }

    private record SplitRequest(
            String newDisplayName,
            List<UUID> sourceRecordIds,
            List<UUID> principalIds,
            String reason) {
    }
}
