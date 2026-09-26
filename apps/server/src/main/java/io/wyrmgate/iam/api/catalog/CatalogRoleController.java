package io.wyrmgate.iam.api.catalog;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.catalog.CatalogRoleApiModels.RolePage;
import io.wyrmgate.iam.api.catalog.CatalogRoleApiModels.RoleResource;
import io.wyrmgate.iam.api.catalog.CatalogRoleApiModels.RoleVersionMemberResource;
import io.wyrmgate.iam.api.catalog.CatalogRoleApiModels.RoleVersionPage;
import io.wyrmgate.iam.api.catalog.CatalogRoleApiModels.RoleVersionResource;
import io.wyrmgate.iam.api.catalog.CatalogRoleApiModels.RoleVersionSummary;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.application.RoleCommandService;
import io.wyrmgate.iam.catalog.application.RoleQueryModels.RoleVersionDetail;
import io.wyrmgate.iam.catalog.application.RoleQueryService;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.catalog.domain.RoleVersion;
import io.wyrmgate.iam.catalog.domain.RoleVersionMember;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
@RequestMapping("/api/v1")
public class CatalogRoleController {

    private static final int DEFAULT_LIMIT = 50;

    private final RoleQueryService queries;
    private final CatalogRoleApiMutationService mutations;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final CatalogCursorCodec cursors;

    public CatalogRoleController(
            RoleQueryService queries,
            CatalogRoleApiMutationService mutations,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            CatalogCursorCodec cursors) {
        this.queries = queries;
        this.mutations = mutations;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping("/roles")
    public ResponseEntity<RolePage> listRoles(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativePermissions.ROLE_READ,
                AdministrativeResource.collection("role"),
                correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        PagePosition position = decodeRoles(
                cursor, actor, correlationId);
        var page = queries.listRoles(
                actor.tenant(), position, effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .body(new RolePage(
                        page.items().stream()
                                .map(CatalogRoleController::resource)
                                .toList(),
                        cursors.encodeRoles(
                                actor.tenant(),
                                page.nextPosition())));
    }

    @PostMapping("/roles")
    public ResponseEntity<RoleResource> createRole(
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId =
                CatalogApiRequestContext.resolveCorrelationId(request, ids);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        Map<String,Object> parsed = exact(
                body,
                Set.of("roleType", "applicationId", "code", "name"),
                correlationId);
        Role.RoleType type = roleType(
                parsed.get("roleType"), correlationId);
        UUID applicationId = optionalUuid(
                parsed.get("applicationId"),
                "applicationId",
                correlationId);
        String code = requireText(
                parsed, "code", 128, correlationId);
        String name = requireText(
                parsed, "name", 512, correlationId);
        if (type == Role.RoleType.BUSINESS
                && applicationId != null) {
            throw CatalogApiException.validation(
                    correlationId,
                    "applicationId",
                    "not_allowed",
                    "BUSINESS Role must not include applicationId.");
        }
        if (type == Role.RoleType.APPLICATION
                && applicationId == null) {
            throw CatalogApiException.validation(
                    correlationId,
                    "applicationId",
                    "required",
                    "APPLICATION Role requires applicationId.");
        }

        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Role created = mutations.createRole(
                actor,
                type,
                applicationId,
                code,
                name,
                key,
                fingerprint(
                        "role:create",
                        type,
                        applicationId,
                        code,
                        name),
                Instant.now(),
                correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, etag(created.revision()))
                .header(
                        HttpHeaders.LOCATION,
                        "/api/v1/roles/" + created.id())
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(created));
    }

    @GetMapping("/roles/{roleId}")
    public ResponseEntity<RoleResource> getRole(
            @PathVariable UUID roleId,
            HttpServletRequest request) {
        UUID correlationId =
                CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativePermissions.ROLE_READ,
                new AdministrativeResource("role", roleId),
                correlationId);
        Role role = queries.findRole(actor.tenant(), roleId)
                .orElseThrow(() ->
                        CatalogApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(role.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(role));
    }

    @PatchMapping("/roles/{roleId}")
    public ResponseEntity<RoleResource> renameRole(
            @PathVariable UUID roleId,
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
                CatalogApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        String name = requireText(
                exact(body, Set.of("name"), correlationId),
                "name",
                512,
                correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Role updated = mutations.renameRole(
                actor,
                roleId,
                name,
                revision,
                key,
                fingerprint(
                        "role:rename",
                        roleId,
                        revision,
                        name),
                Instant.now(),
                correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(updated.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(updated));
    }

    @PostMapping("/roles/{roleId}/retire")
    public ResponseEntity<RoleResource> retireRole(
            @PathVariable UUID roleId,
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
                CatalogApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        Role retired = mutations.retireRole(
                actor,
                roleId,
                revision,
                key,
                fingerprint(
                        "role:retire",
                        roleId,
                        revision),
                Instant.now(),
                correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(retired.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(retired));
    }

    @GetMapping("/roles/{roleId}/versions")
    public ResponseEntity<RoleVersionPage> listVersions(
            @PathVariable UUID roleId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId =
                CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativePermissions.ROLE_VERSION_READ,
                AdministrativeResource.collection("role-version"),
                correlationId);
        if (queries.findRole(actor.tenant(), roleId).isEmpty()) {
            throw CatalogApiException.notFound(correlationId);
        }
        int effectiveLimit = validateLimit(limit, correlationId);
        PagePosition position = decodeRoleVersions(
                cursor, actor, roleId, correlationId);
        var page = queries.listVersions(
                actor.tenant(),
                roleId,
                position,
                effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .body(new RoleVersionPage(
                        page.items().stream()
                                .map(CatalogRoleController::summary)
                                .toList(),
                        cursors.encodeRoleVersions(
                                actor.tenant(),
                                roleId,
                                page.nextPosition())));
    }

    @PostMapping("/roles/{roleId}/versions")
    public ResponseEntity<RoleVersionResource> createVersion(
            @PathVariable UUID roleId,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        UUID correlationId =
                CatalogApiRequestContext.resolveCorrelationId(request, ids);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        Map<String,Object> parsed = exact(
                body, Set.of("members"), correlationId);
        List<RoleCommandService.MemberSpec> members =
                members(parsed.get("members"), correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        RoleVersion created = mutations.createVersion(
                actor,
                roleId,
                members,
                key,
                memberFingerprint(roleId, members),
                Instant.now(),
                correlationId);
        RoleVersionDetail detail = queries.findVersion(
                        actor.tenant(),
                        roleId,
                        created.id())
                .orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(
                        HttpHeaders.ETAG,
                        etag(created.revision()))
                .header(
                        HttpHeaders.LOCATION,
                        "/api/v1/roles/" + roleId
                                + "/versions/" + created.id())
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(detail));
    }

    @GetMapping("/roles/{roleId}/versions/{roleVersionId}")
    public ResponseEntity<RoleVersionResource> getVersion(
            @PathVariable UUID roleId,
            @PathVariable UUID roleVersionId,
            HttpServletRequest request) {
        UUID correlationId =
                CatalogApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativePermissions.ROLE_VERSION_READ,
                new AdministrativeResource(
                        "role-version", roleVersionId),
                correlationId);
        RoleVersionDetail detail = queries.findVersion(
                        actor.tenant(),
                        roleId,
                        roleVersionId)
                .orElseThrow(() ->
                        CatalogApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(detail.version().revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(detail));
    }

    @PostMapping(
            "/roles/{roleId}/versions/{roleVersionId}/validate")
    public ResponseEntity<RoleVersionResource> validateVersion(
            @PathVariable UUID roleId,
            @PathVariable UUID roleVersionId,
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
                CatalogApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        RoleVersion ready = mutations.validateVersion(
                actor,
                roleId,
                roleVersionId,
                revision,
                key,
                fingerprint(
                        "role-version:validate",
                        roleId,
                        roleVersionId,
                        revision),
                Instant.now(),
                correlationId);
        return versionResponse(
                actor,
                roleId,
                ready,
                correlationId);
    }

    @PostMapping(
            "/roles/{roleId}/versions/{roleVersionId}/activate")
    public ResponseEntity<RoleVersionResource> activateVersion(
            @PathVariable UUID roleId,
            @PathVariable UUID roleVersionId,
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
                CatalogApiRequestContext.resolveCorrelationId(request, ids);
        long revision = parseIfMatch(ifMatch, correlationId);
        String key = validateIdempotencyKey(
                idempotencyKey, correlationId);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        RoleVersion active = mutations.activateVersion(
                actor,
                roleId,
                roleVersionId,
                revision,
                key,
                fingerprint(
                        "role-version:activate",
                        roleId,
                        roleVersionId,
                        revision),
                Instant.now(),
                correlationId);
        return versionResponse(
                actor,
                roleId,
                active,
                correlationId);
    }

    private ResponseEntity<RoleVersionResource> versionResponse(
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            RoleVersion version,
            UUID correlationId) {
        RoleVersionDetail detail = queries.findVersion(
                        actor.tenant(), roleId, version.id())
                .orElseThrow();
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        etag(version.revision()))
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(detail));
    }

    private void requireRead(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(
                actor,
                permission,
                resource,
                Instant.now()).allowed()) {
            throw CatalogApiException.forbidden(correlationId);
        }
    }

    private PagePosition decodeRoles(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeRoles(cursor, actor.tenant());
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private PagePosition decodeRoleVersions(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID roleId,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeRoleVersions(
                    cursor, actor.tenant(), roleId);
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor(correlationId);
        }
    }

    private static CatalogApiException invalidCursor(
            UUID correlationId) {
        return CatalogApiException.validation(
                correlationId,
                "cursor",
                "invalid_cursor",
                "cursor is invalid or malformed.");
    }

    private static int validateLimit(
            Integer limit, UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > 200) {
            throw CatalogApiException.validation(
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
                || !value.matches("\"rev-[1-9][0-9]*\"")) {
            throw CatalogApiException.validation(
                    correlationId,
                    "If-Match",
                    "invalid_revision_etag",
                    "If-Match must be a strong revision ETag such as \"rev-7\".");
        }
        try {
            return Long.parseLong(
                    value.substring(5, value.length() - 1));
        } catch (NumberFormatException invalid) {
            throw CatalogApiException.validation(
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
            throw CatalogApiException.validation(
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
        if (body == null || !body.keySet().equals(fields)) {
            throw CatalogApiException.validation(
                    correlationId,
                    "request",
                    "unexpected_fields",
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
                    correlationId,
                    field,
                    "invalid_text",
                    field + " must be non-blank and at most "
                            + max + " characters.");
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
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string or null.");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw CatalogApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string or null.");
        }
    }

    private static Role.RoleType roleType(
            Object value, UUID correlationId) {
        if (!(value instanceof String text)) {
            throw CatalogApiException.validation(
                    correlationId,
                    "roleType",
                    "invalid_enum",
                    "roleType must be BUSINESS or APPLICATION.");
        }
        try {
            return Role.RoleType.valueOf(text);
        } catch (IllegalArgumentException invalid) {
            throw CatalogApiException.validation(
                    correlationId,
                    "roleType",
                    "invalid_enum",
                    "roleType must be BUSINESS or APPLICATION.");
        }
    }

    private static List<RoleCommandService.MemberSpec> members(
            Object value, UUID correlationId) {
        if (!(value instanceof List<?> values)
                || values.isEmpty()
                || values.size() > 1000) {
            throw CatalogApiException.validation(
                    correlationId,
                    "members",
                    "invalid_cardinality",
                    "members must contain between 1 and 1000 entries.");
        }
        List<RoleCommandService.MemberSpec> result =
                new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Object item = values.get(index);
            if (!(item instanceof Map<?,?> raw)) {
                throw memberError(correlationId, index);
            }
            Map<String,Object> member = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw memberError(correlationId, index);
                }
                member.put(key, entry.getValue());
            }
            Object kindValue = member.get("kind");
            if (!(kindValue instanceof String kind)) {
                throw memberError(correlationId, index);
            }
            switch (kind) {
                case "ENTITLEMENT" -> {
                    if (!member.keySet().equals(
                            Set.of("kind", "entitlementId"))) {
                        throw memberError(correlationId, index);
                    }
                    result.add(
                            RoleCommandService.MemberSpec.entitlement(
                                    requiredUuid(
                                            member.get("entitlementId"),
                                            "members[" + index
                                                    + "].entitlementId",
                                            correlationId)));
                }
                case "APPLICATION_ROLE" -> {
                    if (!member.keySet().equals(
                            Set.of("kind", "roleId"))) {
                        throw memberError(correlationId, index);
                    }
                    result.add(
                            RoleCommandService.MemberSpec.applicationRole(
                                    requiredUuid(
                                            member.get("roleId"),
                                            "members[" + index
                                                    + "].roleId",
                                            correlationId)));
                }
                default -> throw memberError(
                        correlationId, index);
            }
        }
        return List.copyOf(result);
    }

    private static UUID requiredUuid(
            Object value,
            String field,
            UUID correlationId) {
        UUID parsed = optionalUuid(
                value, field, correlationId);
        if (parsed == null) {
            throw CatalogApiException.validation(
                    correlationId,
                    field,
                    "required",
                    field + " is required.");
        }
        return parsed;
    }

    private static CatalogApiException memberError(
            UUID correlationId, int index) {
        return CatalogApiException.validation(
                correlationId,
                "members[" + index + "]",
                "invalid_member",
                "RoleVersion member must be ENTITLEMENT with entitlementId or APPLICATION_ROLE with roleId.");
    }

    private static RequestFingerprint memberFingerprint(
            UUID roleId,
            List<RoleCommandService.MemberSpec> members) {
        List<String> canonical = members.stream()
                .map(member ->
                        member.kind().name()
                                + ":" + member.targetId())
                .sorted()
                .toList();
        return fingerprint(
                "role-version:create",
                roleId,
                String.join("|", canonical));
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

    private static RoleResource resource(Role value) {
        return new RoleResource(
                value.id(),
                value.type().name(),
                value.applicationId(),
                value.code(),
                value.name(),
                value.lifecycleState().name(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private static RoleVersionSummary summary(
            RoleVersion value) {
        return new RoleVersionSummary(
                value.id(),
                value.roleId(),
                value.versionNumber(),
                value.state().name(),
                value.contentHash(),
                value.revision(),
                value.activatedAt(),
                value.createdAt(),
                value.updatedAt());
    }

    private static RoleVersionResource resource(
            RoleVersionDetail detail) {
        RoleVersion value = detail.version();
        return new RoleVersionResource(
                value.id(),
                value.roleId(),
                value.versionNumber(),
                value.state().name(),
                value.contentHash(),
                value.revision(),
                value.activatedAt(),
                value.createdAt(),
                value.updatedAt(),
                detail.members().stream()
                        .map(CatalogRoleController::member)
                        .toList());
    }

    private static RoleVersionMemberResource member(
            RoleVersionMember value) {
        return new RoleVersionMemberResource(
                value.kind().name(),
                value.memberRoleId(),
                value.memberEntitlementId(),
                value.ordinal());
    }
}
