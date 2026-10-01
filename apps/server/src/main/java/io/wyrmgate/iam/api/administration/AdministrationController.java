package io.wyrmgate.iam.api.administration;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorityService;
import io.wyrmgate.iam.administration.application.AdministrativeBreakGlassService;
import io.wyrmgate.iam.administration.application.AdministrativeElevationService;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeDelegation;
import io.wyrmgate.iam.administration.domain.AdministrativeElevation;
import io.wyrmgate.iam.administration.domain.AdministrativeGrant;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AdministrativeScopeType;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.*;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
@RequestMapping("/api/v1")
public final class AdministrationController {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final AdministrativeAuthorityService authority;
    private final AdministrativeElevationService elevations;
    private final AdministrativeBreakGlassService breakGlass;
    private final AdministrationApiMutationService mutations;
    private final AdministrationCursorCodec cursors;
    private final IdGenerator ids;

    public AdministrationController(
            AdministrativeAuthorityService authority,
            AdministrativeElevationService elevations,
            AdministrativeBreakGlassService breakGlass,
            AdministrationApiMutationService mutations,
            AdministrationCursorCodec cursors,
            IdGenerator ids) {
        this.authority = authority;
        this.elevations = elevations;
        this.breakGlass = breakGlass;
        this.mutations = mutations;
        this.cursors = cursors;
        this.ids = ids;
    }

    @GetMapping("/administrative-roles")
    public ResponseEntity<RolePage> listRoles(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        int size = limit(limit, ctx.correlationId());
        AdministrationCursorCodec.Position position =
                decode(cursor, "administrative-role", ctx, ctx.correlationId());
        List<AdministrativeRole> values = authority.listRoles(
                ctx.actor(), createdAt(position), id(position), size, Instant.now());
        return ok(new RolePage(
                values.stream().map(AdministrationController::resource).toList(),
                next("administrative-role", ctx, values, size,
                        AdministrativeRole::createdAt, AdministrativeRole::id)),
                ctx.correlationId());
    }

    @PostMapping("/administrative-roles")
    public ResponseEntity<AdministrativeRoleResource> createRole(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        String key = idempotency(idempotencyKey, ctx.correlationId());
        Map<String,Object> parsed = exact(body, Set.of("code", "name", "permissions"), ctx.correlationId());
        String code = text(parsed, "code", 128, ctx.correlationId());
        String name = text(parsed, "name", 512, ctx.correlationId());
        Set<AdministrativePermission> permissions = permissions(parsed.get("permissions"), ctx.correlationId());
        AdministrativeRole value = mutations.createRole(
                ctx.actor(), code, name, permissions, key,
                fingerprint("role:create", code, name, permissionFingerprint(permissions)),
                Instant.now(), ctx.correlationId());
        return created(resource(value), "/api/v1/administrative-roles/" + value.id(),
                value.revision(), ctx.correlationId());
    }

    @GetMapping("/administrative-roles/{roleId}")
    public ResponseEntity<AdministrativeRoleResource> getRole(
            @PathVariable UUID roleId, HttpServletRequest request) {
        RequestContext ctx = context(request);
        AdministrativeRole value = authority.getRole(ctx.actor(), roleId, Instant.now());
        return ok(resource(value), value.revision(), ctx.correlationId());
    }

    @PatchMapping("/administrative-roles/{roleId}")
    public ResponseEntity<AdministrativeRoleResource> renameRole(
            @PathVariable UUID roleId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        long revision = revision(ifMatch, ctx.correlationId());
        String key = idempotency(idempotencyKey, ctx.correlationId());
        String name = text(exact(body, Set.of("name"), ctx.correlationId()), "name", 512, ctx.correlationId());
        AdministrativeRole value = mutations.renameRole(
                ctx.actor(), roleId, name, revision, key,
                fingerprint("role:rename", roleId, revision, name), Instant.now(), ctx.correlationId());
        return ok(resource(value), value.revision(), ctx.correlationId());
    }

    @PostMapping("/administrative-roles/{roleId}:add-permission")
    public ResponseEntity<AdministrativeRoleResource> addPermission(
            @PathVariable UUID roleId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        return mutatePermission(roleId, ifMatch, idempotencyKey, body, true, request);
    }

    @PostMapping("/administrative-roles/{roleId}:remove-permission")
    public ResponseEntity<AdministrativeRoleResource> removePermission(
            @PathVariable UUID roleId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        return mutatePermission(roleId, ifMatch, idempotencyKey, body, false, request);
    }

    private ResponseEntity<AdministrativeRoleResource> mutatePermission(
            UUID roleId, String ifMatch, String idempotencyKey, Map<String,Object> body,
            boolean add, HttpServletRequest request) {
        RequestContext ctx = context(request);
        long revision = revision(ifMatch, ctx.correlationId());
        String key = idempotency(idempotencyKey, ctx.correlationId());
        String permissionKey = text(
                exact(body, Set.of("permission"), ctx.correlationId()),
                "permission", 256, ctx.correlationId());
        AdministrativePermission permission = permission(permissionKey, ctx.correlationId());
        AdministrativeRole value = add
                ? mutations.addPermission(ctx.actor(), roleId, permission, revision, key,
                        fingerprint("role:permission:add", roleId, revision, permission.key()),
                        Instant.now(), ctx.correlationId())
                : mutations.removePermission(ctx.actor(), roleId, permission, revision, key,
                        fingerprint("role:permission:remove", roleId, revision, permission.key()),
                        Instant.now(), ctx.correlationId());
        return ok(resource(value), value.revision(), ctx.correlationId());
    }

    @GetMapping("/administrative-grants")
    public ResponseEntity<GrantPage> listGrants(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        int size = limit(limit, ctx.correlationId());
        AdministrationCursorCodec.Position position = decode(cursor, "administrative-grant", ctx, ctx.correlationId());
        List<AdministrativeGrant> values = authority.listGrants(
                ctx.actor(), createdAt(position), id(position), size, Instant.now());
        return ok(new GrantPage(
                values.stream().map(AdministrationController::resource).toList(),
                next("administrative-grant", ctx, values, size,
                        AdministrativeGrant::createdAt, AdministrativeGrant::id)),
                ctx.correlationId());
    }

    @PostMapping("/administrative-grants")
    public ResponseEntity<AdministrativeGrantResource> createGrant(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        String key = idempotency(idempotencyKey, ctx.correlationId());
        Map<String,Object> parsed = allowed(
                body,
                Set.of("beneficiaryIdentityId", "roleId", "scope", "validFrom", "validUntil",
                        "grantable", "delegable", "authorityBasisGrantId"),
                Set.of("beneficiaryIdentityId", "roleId", "scope", "grantable", "delegable", "authorityBasisGrantId"),
                ctx.correlationId());
        UUID beneficiary = uuid(parsed, "beneficiaryIdentityId", ctx.correlationId());
        UUID role = uuid(parsed, "roleId", ctx.correlationId());
        AdministrativeScope scope = scope(parsed.get("scope"), ctx.correlationId());
        Instant validFrom = optionalInstant(parsed.get("validFrom"), "validFrom", ctx.correlationId());
        Instant validUntil = optionalInstant(parsed.get("validUntil"), "validUntil", ctx.correlationId());
        boolean grantable = bool(parsed, "grantable", ctx.correlationId());
        boolean delegable = bool(parsed, "delegable", ctx.correlationId());
        UUID basis = uuid(parsed, "authorityBasisGrantId", ctx.correlationId());
        AdministrativeGrant value = mutations.createGrant(
                ctx.actor(), beneficiary, role, scope, validFrom, validUntil, grantable, delegable, basis,
                key, fingerprint("grant:create", beneficiary, role, scopeString(scope), validFrom, validUntil,
                        grantable, delegable, basis), Instant.now(), ctx.correlationId());
        return created(resource(value), "/api/v1/administrative-grants/" + value.id(),
                value.revision(), ctx.correlationId());
    }

    @GetMapping("/administrative-grants/{grantId}")
    public ResponseEntity<AdministrativeGrantResource> getGrant(
            @PathVariable UUID grantId, HttpServletRequest request) {
        RequestContext ctx = context(request);
        AdministrativeGrant value = authority.getGrant(ctx.actor(), grantId, Instant.now());
        return ok(resource(value), value.revision(), ctx.correlationId());
    }

    @PostMapping("/administrative-grants/{grantId}:revoke")
    public ResponseEntity<AdministrativeGrantResource> revokeGrant(
            @PathVariable UUID grantId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        long rev = revision(ifMatch, ctx.correlationId());
        String key = idempotency(idempotencyKey, ctx.correlationId());
        AdministrativeGrant value = mutations.revokeGrant(
                ctx.actor(), grantId, rev, key, fingerprint("grant:revoke", grantId, rev),
                Instant.now(), ctx.correlationId());
        return ok(resource(value), value.revision(), ctx.correlationId());
    }

    @GetMapping("/administrative-delegations")
    public ResponseEntity<DelegationPage> listDelegations(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        int size = limit(limit, ctx.correlationId());
        AdministrationCursorCodec.Position position = decode(cursor, "administrative-delegation", ctx, ctx.correlationId());
        List<AdministrativeDelegation> values = authority.listDelegations(
                ctx.actor(), createdAt(position), id(position), size, Instant.now());
        return ok(new DelegationPage(
                values.stream().map(AdministrationController::resource).toList(),
                next("administrative-delegation", ctx, values, size,
                        AdministrativeDelegation::createdAt, AdministrativeDelegation::id)),
                ctx.correlationId());
    }

    @PostMapping("/administrative-delegations")
    public ResponseEntity<AdministrativeDelegationResource> createDelegation(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        String key = idempotency(idempotencyKey, ctx.correlationId());
        Map<String,Object> parsed = allowed(
                body, Set.of("delegateIdentityId", "sourceGrantId", "scope", "validFrom", "validUntil"),
                Set.of("delegateIdentityId", "sourceGrantId", "scope", "validUntil"),
                ctx.correlationId());
        UUID delegate = uuid(parsed, "delegateIdentityId", ctx.correlationId());
        UUID source = uuid(parsed, "sourceGrantId", ctx.correlationId());
        AdministrativeScope scope = scope(parsed.get("scope"), ctx.correlationId());
        Instant validFrom = optionalInstant(parsed.get("validFrom"), "validFrom", ctx.correlationId());
        Instant validUntil = requiredInstant(parsed.get("validUntil"), "validUntil", ctx.correlationId());
        AdministrativeDelegation value = mutations.createDelegation(
                ctx.actor(), delegate, source, scope, validFrom, validUntil, key,
                fingerprint("delegation:create", delegate, source, scopeString(scope), validFrom, validUntil),
                Instant.now(), ctx.correlationId());
        return created(resource(value), "/api/v1/administrative-delegations/" + value.id(),
                value.revision(), ctx.correlationId());
    }

    @GetMapping("/administrative-delegations/{delegationId}")
    public ResponseEntity<AdministrativeDelegationResource> getDelegation(
            @PathVariable UUID delegationId, HttpServletRequest request) {
        RequestContext ctx = context(request);
        AdministrativeDelegation value = authority.getDelegation(ctx.actor(), delegationId, Instant.now());
        return ok(resource(value), value.revision(), ctx.correlationId());
    }

    @PostMapping("/administrative-delegations/{delegationId}:revoke")
    public ResponseEntity<AdministrativeDelegationResource> revokeDelegation(
            @PathVariable UUID delegationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        long rev = revision(ifMatch, ctx.correlationId());
        String key = idempotency(idempotencyKey, ctx.correlationId());
        AdministrativeDelegation value = mutations.revokeDelegation(
                ctx.actor(), delegationId, rev, key,
                fingerprint("delegation:revoke", delegationId, rev), Instant.now(), ctx.correlationId());
        return ok(resource(value), value.revision(), ctx.correlationId());
    }

    @GetMapping("/administrative-elevations")
    public ResponseEntity<ElevationPage> listElevations(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        int size = limit(limit, ctx.correlationId());
        AdministrationCursorCodec.Position position = decode(cursor, "administrative-elevation", ctx, ctx.correlationId());
        List<AdministrativeElevation> values = elevations.list(
                ctx.actor(), createdAt(position), id(position), size, Instant.now());
        return ok(new ElevationPage(
                values.stream().map(AdministrationController::resource).toList(),
                next("administrative-elevation", ctx, values, size,
                        AdministrativeElevation::createdAt, AdministrativeElevation::id)),
                ctx.correlationId());
    }

    @PostMapping("/administrative-elevations")
    public ResponseEntity<AdministrativeElevationResource> requestElevation(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        String key = idempotency(idempotencyKey, ctx.correlationId());
        Map<String,Object> parsed = allowed(
                body,
                Set.of("beneficiaryIdentityId", "roleId", "scope", "validFrom", "validUntil", "authorityBasisGrantId"),
                Set.of("beneficiaryIdentityId", "roleId", "scope", "validUntil", "authorityBasisGrantId"),
                ctx.correlationId());
        UUID beneficiary = uuid(parsed, "beneficiaryIdentityId", ctx.correlationId());
        UUID role = uuid(parsed, "roleId", ctx.correlationId());
        AdministrativeScope scope = scope(parsed.get("scope"), ctx.correlationId());
        Instant validFrom = optionalInstant(parsed.get("validFrom"), "validFrom", ctx.correlationId());
        Instant validUntil = requiredInstant(parsed.get("validUntil"), "validUntil", ctx.correlationId());
        UUID basis = uuid(parsed, "authorityBasisGrantId", ctx.correlationId());
        AdministrativeElevation value = mutations.requestElevation(
                ctx.actor(), beneficiary, role, scope, validFrom, validUntil, basis, key,
                fingerprint("elevation:request", beneficiary, role, scopeString(scope), validFrom, validUntil, basis),
                Instant.now(), ctx.correlationId());
        return created(resource(value), "/api/v1/administrative-elevations/" + value.id(),
                value.revision(), ctx.correlationId());
    }

    @GetMapping("/administrative-elevations/{elevationId}")
    public ResponseEntity<AdministrativeElevationResource> getElevation(
            @PathVariable UUID elevationId, HttpServletRequest request) {
        RequestContext ctx = context(request);
        AdministrativeElevation value = elevations.get(ctx.actor(), elevationId, Instant.now());
        return ok(resource(value), value.revision(), ctx.correlationId());
    }

    @PostMapping("/administrative-elevations/{elevationId}:request-approval")
    public ResponseEntity<AdministrativeElevationResource> requestElevationApproval(
            @PathVariable UUID elevationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return elevationOperation(elevationId, ifMatch, idempotencyKey, "request-approval", request);
    }

    @PostMapping("/administrative-elevations/{elevationId}:apply")
    public ResponseEntity<AdministrativeElevationResource> applyElevation(
            @PathVariable UUID elevationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return elevationOperation(elevationId, ifMatch, idempotencyKey, "apply", request);
    }

    @PostMapping("/administrative-elevations/{elevationId}:cancel")
    public ResponseEntity<AdministrativeElevationResource> cancelElevation(
            @PathVariable UUID elevationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return elevationOperation(elevationId, ifMatch, idempotencyKey, "cancel", request);
    }

    @PostMapping("/administrative-elevations/{elevationId}:revoke")
    public ResponseEntity<AdministrativeElevationResource> revokeElevation(
            @PathVariable UUID elevationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return elevationOperation(elevationId, ifMatch, idempotencyKey, "revoke", request);
    }

    private ResponseEntity<AdministrativeElevationResource> elevationOperation(
            UUID id, String ifMatch, String idempotencyKey, String operation, HttpServletRequest request) {
        RequestContext ctx = context(request);
        long rev = revision(ifMatch, ctx.correlationId());
        String key = idempotency(idempotencyKey, ctx.correlationId());
        RequestFingerprint fp = fingerprint("elevation:" + operation, id, rev);
        Instant now = Instant.now();
        AdministrativeElevation value = switch (operation) {
            case "request-approval" -> mutations.requestElevationApproval(ctx.actor(), id, rev, key, fp, now, ctx.correlationId());
            case "apply" -> mutations.applyElevation(ctx.actor(), id, rev, key, fp, now, ctx.correlationId());
            case "cancel" -> mutations.cancelElevation(ctx.actor(), id, rev, key, fp, now, ctx.correlationId());
            case "revoke" -> mutations.revokeElevation(ctx.actor(), id, rev, key, fp, now, ctx.correlationId());
            default -> throw new IllegalStateException("unknown elevation operation");
        };
        return ok(resource(value), value.revision(), ctx.correlationId());
    }

    @GetMapping("/administrative-break-glass-operations")
    public ResponseEntity<BreakGlassPage> listBreakGlass(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        int size = limit(limit, ctx.correlationId());
        AdministrationCursorCodec.Position position = decode(cursor, "administrative-break-glass", ctx, ctx.correlationId());
        List<AdministrativeBreakGlassOperation> values = breakGlass.list(
                ctx.actor(), createdAt(position), id(position), size, Instant.now());
        return ok(new BreakGlassPage(
                values.stream().map(AdministrationController::resource).toList(),
                next("administrative-break-glass", ctx, values, size,
                        AdministrativeBreakGlassOperation::createdAt, AdministrativeBreakGlassOperation::id)),
                ctx.correlationId());
    }

    @PostMapping("/administrative-break-glass-operations")
    public ResponseEntity<AdministrativeBreakGlassResource> activateBreakGlass(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        String key = idempotency(idempotencyKey, ctx.correlationId());
        Map<String,Object> parsed = exact(
                body, Set.of("roleId", "scope", "validUntil", "reason", "incidentReference"), ctx.correlationId());
        UUID role = uuid(parsed, "roleId", ctx.correlationId());
        AdministrativeScope scope = scope(parsed.get("scope"), ctx.correlationId());
        Instant validUntil = requiredInstant(parsed.get("validUntil"), "validUntil", ctx.correlationId());
        String reason = text(parsed, "reason", 2048, ctx.correlationId());
        String incident = text(parsed, "incidentReference", 512, ctx.correlationId());
        AdministrativeBreakGlassOperation value = mutations.activateBreakGlass(
                ctx.actor(), role, scope, validUntil, reason, incident, key,
                fingerprint("break-glass:activate", role, scopeString(scope), validUntil, reason, incident),
                Instant.now(), ctx.correlationId());
        return created(resource(value), "/api/v1/administrative-break-glass-operations/" + value.id(),
                value.revision(), ctx.correlationId());
    }

    @GetMapping("/administrative-break-glass-operations/{operationId}")
    public ResponseEntity<AdministrativeBreakGlassResource> getBreakGlass(
            @PathVariable UUID operationId, HttpServletRequest request) {
        RequestContext ctx = context(request);
        AdministrativeBreakGlassOperation value = breakGlass.get(ctx.actor(), operationId, Instant.now());
        return ok(resource(value), value.revision(), ctx.correlationId());
    }

    @PostMapping("/administrative-break-glass-operations/{operationId}:revoke")
    public ResponseEntity<AdministrativeBreakGlassResource> revokeBreakGlass(
            @PathVariable UUID operationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        RequestContext ctx = context(request);
        long rev = revision(ifMatch, ctx.correlationId());
        String key = idempotency(idempotencyKey, ctx.correlationId());
        AdministrativeBreakGlassOperation value = mutations.revokeBreakGlass(
                ctx.actor(), operationId, rev, key, fingerprint("break-glass:revoke", operationId, rev),
                Instant.now(), ctx.correlationId());
        return ok(resource(value), value.revision(), ctx.correlationId());
    }

    private RequestContext context(HttpServletRequest request) {
        UUID correlationId = AdministrationApiRequestContext.resolveCorrelationId(request, ids);
        return new RequestContext(ControlPlaneActorRequestContext.require(request), correlationId);
    }

    private AdministrationCursorCodec.Position decode(
            String cursor, String kind, RequestContext ctx, UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decode(cursor, kind, ctx.actor().tenant());
        } catch (IllegalArgumentException invalid) {
            throw AdministrationApiException.validation(
                    correlationId, "cursor", "invalid_cursor",
                    "cursor is invalid or does not match this Administration collection.");
        }
    }

    private <T> String next(
            String kind, RequestContext ctx, List<T> values, int limit,
            java.util.function.Function<T,Instant> createdAt,
            java.util.function.Function<T,UUID> id) {
        if (values.size() < limit || values.isEmpty()) return null;
        T last = values.get(values.size() - 1);
        return cursors.encode(kind, ctx.actor().tenant(),
                new AdministrationCursorCodec.Position(createdAt.apply(last), id.apply(last)));
    }

    private static Instant createdAt(AdministrationCursorCodec.Position position) {
        return position == null ? null : position.createdAt();
    }

    private static UUID id(AdministrationCursorCodec.Position position) {
        return position == null ? null : position.id();
    }

    private static int limit(Integer requested, UUID correlationId) {
        int value = requested == null ? DEFAULT_LIMIT : requested;
        if (value < 1 || value > MAX_LIMIT) {
            throw AdministrationApiException.validation(
                    correlationId, "limit", "out_of_range", "limit must be between 1 and 200.");
        }
        return value;
    }

    private static String idempotency(String key, UUID correlationId) {
        if (key == null || key.isBlank() || key.length() < 8 || key.length() > 200) {
            throw AdministrationApiException.validation(
                    correlationId, "Idempotency-Key", "invalid_length",
                    "Idempotency-Key must contain between 8 and 200 characters.");
        }
        return key;
    }

    private static long revision(String value, UUID correlationId) {
        if (value == null || !value.matches("\\\"rev-[1-9][0-9]*\\\"")) {
            throw AdministrationApiException.validation(
                    correlationId, "If-Match", "invalid_revision",
                    "If-Match must be a strong revision ETag such as \"rev-3\".");
        }
        return Long.parseLong(value.substring(5, value.length() - 1));
    }

    private static Map<String,Object> exact(Map<String,Object> body, Set<String> fields, UUID correlationId) {
        if (body == null || !body.keySet().equals(fields)) {
            throw AdministrationApiException.validation(
                    correlationId, "request", "unexpected_fields",
                    "Request body fields do not match the operation contract.");
        }
        return new LinkedHashMap<>(body);
    }

    private static Map<String,Object> allowed(
            Map<String,Object> body, Set<String> allowed, Set<String> required, UUID correlationId) {
        if (body == null || !allowed.containsAll(body.keySet()) || !body.keySet().containsAll(required)) {
            throw AdministrationApiException.validation(
                    correlationId, "request", "unexpected_fields",
                    "Request body fields do not match the operation contract.");
        }
        return new LinkedHashMap<>(body);
    }

    private static String text(Map<String,Object> body, String field, int max, UUID correlationId) {
        Object value = body.get(field);
        if (!(value instanceof String text) || text.isBlank() || text.trim().length() > max) {
            throw AdministrationApiException.validation(
                    correlationId, field, "invalid_text",
                    field + " must be non-blank and at most " + max + " characters.");
        }
        return text.trim();
    }

    private static UUID uuid(Map<String,Object> body, String field, UUID correlationId) {
        Object value = body.get(field);
        if (!(value instanceof String text)) {
            throw AdministrationApiException.validation(
                    correlationId, field, "invalid_uuid", field + " must be a UUID string.");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw AdministrationApiException.validation(
                    correlationId, field, "invalid_uuid", field + " must be a UUID string.");
        }
    }

    private static boolean bool(Map<String,Object> body, String field, UUID correlationId) {
        Object value = body.get(field);
        if (!(value instanceof Boolean result)) {
            throw AdministrationApiException.validation(
                    correlationId, field, "invalid_boolean", field + " must be boolean.");
        }
        return result;
    }

    private static Instant optionalInstant(Object value, String field, UUID correlationId) {
        return value == null ? null : requiredInstant(value, field, correlationId);
    }

    private static Instant requiredInstant(Object value, String field, UUID correlationId) {
        if (!(value instanceof String text)) {
            throw AdministrationApiException.validation(
                    correlationId, field, "invalid_instant", field + " must be an ISO-8601 instant string.");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            throw AdministrationApiException.validation(
                    correlationId, field, "invalid_instant", field + " must be an ISO-8601 instant string.");
        }
    }

    private static Set<AdministrativePermission> permissions(Object value, UUID correlationId) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 200) {
            throw AdministrationApiException.validation(
                    correlationId, "permissions", "invalid_array",
                    "permissions must contain between 1 and 200 semantic permission keys.");
        }
        Set<AdministrativePermission> result = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw AdministrationApiException.validation(
                        correlationId, "permissions", "invalid_permission",
                        "permissions must contain semantic permission strings.");
            }
            result.add(permission(text, correlationId));
        }
        if (result.size() != list.size()) {
            throw AdministrationApiException.validation(
                    correlationId, "permissions", "duplicate_value", "permissions must not contain duplicates.");
        }
        return Set.copyOf(result);
    }

    private static AdministrativePermission permission(String value, UUID correlationId) {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':') || separator == value.length() - 1) {
            throw AdministrationApiException.validation(
                    correlationId, "permission", "invalid_permission",
                    "permission must use resource:action semantic form.");
        }
        try {
            return new AdministrativePermission(value.substring(0, separator), value.substring(separator + 1));
        } catch (IllegalArgumentException invalid) {
            throw AdministrationApiException.validation(
                    correlationId, "permission", "invalid_permission",
                    "permission must use lower-case semantic tokens.");
        }
    }

    private static AdministrativeScope scope(Object value, UUID correlationId) {
        if (!(value instanceof Map<?,?> raw)) {
            throw AdministrationApiException.validation(
                    correlationId, "scope", "invalid_scope", "scope must be an object.");
        }
        Map<String,Object> map = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw AdministrationApiException.validation(
                        correlationId, "scope", "invalid_scope", "scope property names must be strings.");
            }
            map.put(key, entry.getValue());
        }
        if (!Set.of("type", "resourceType", "resourceId", "scopeKey").containsAll(map.keySet())
                || !map.containsKey("type")) {
            throw AdministrationApiException.validation(
                    correlationId, "scope", "invalid_scope", "scope contains unsupported fields.");
        }
        String typeText = text(map, "type", 64, correlationId);
        AdministrativeScopeType type;
        try {
            type = AdministrativeScopeType.valueOf(typeText);
        } catch (IllegalArgumentException invalid) {
            throw AdministrationApiException.validation(
                    correlationId, "scope.type", "invalid_enum", "scope.type is unsupported.");
        }
        try {
            return switch (type) {
                case GLOBAL -> {
                    requireAbsent(map, Set.of("resourceType", "resourceId", "scopeKey"), correlationId);
                    yield AdministrativeScope.global();
                }
                case SPECIFIC_RESOURCE -> {
                    requireAbsent(map, Set.of("scopeKey"), correlationId);
                    String resourceType = text(map, "resourceType", 128, correlationId);
                    UUID resourceId = uuid(map, "resourceId", correlationId);
                    yield AdministrativeScope.specificResource(resourceType, resourceId);
                }
                case CANONICAL_ATTRIBUTE_CLASSIFICATION -> {
                    requireAbsent(map, Set.of("resourceType", "resourceId"), correlationId);
                    yield AdministrativeScope.canonicalAttributeClassification(
                            text(map, "scopeKey", 128, correlationId));
                }
                default -> {
                    requireAbsent(map, Set.of("resourceType", "scopeKey"), correlationId);
                    yield new AdministrativeScope(type, null, uuid(map, "resourceId", correlationId), null);
                }
            };
        } catch (IllegalArgumentException invalid) {
            throw AdministrationApiException.validation(
                    correlationId, "scope", "invalid_scope", "scope does not match its declared type.");
        }
    }

    private static void requireAbsent(Map<String,Object> map, Set<String> fields, UUID correlationId) {
        for (String field : fields) {
            if (map.containsKey(field) && map.get(field) != null) {
                throw AdministrationApiException.validation(
                        correlationId, "scope." + field, "unexpected_field",
                        field + " is not valid for this scope type.");
            }
        }
    }

    private static RequestFingerprint fingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) canonical.append(value == null ? "<null>" : value).append('\u0000');
        return RequestFingerprint.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String permissionFingerprint(Set<AdministrativePermission> permissions) {
        return permissions.stream().map(AdministrativePermission::key).sorted()
                .reduce("", (a, b) -> a + b + ",");
    }

    private static String scopeString(AdministrativeScope scope) {
        return scope.type() + "|" + scope.resourceType() + "|" + scope.resourceId() + "|" + scope.scopeKey();
    }

    private static String etag(long revision) { return "\"rev-" + revision + "\""; }

    private static <T> ResponseEntity<T> ok(T body, UUID correlationId) {
        return ResponseEntity.ok().header("X-Correlation-Id", correlationId.toString()).body(body);
    }

    private static <T> ResponseEntity<T> ok(T body, long revision, UUID correlationId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(revision))
                .header("X-Correlation-Id", correlationId.toString())
                .body(body);
    }

    private static <T> ResponseEntity<T> created(
            T body, String location, long revision, UUID correlationId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, location)
                .header(HttpHeaders.ETAG, etag(revision))
                .header("X-Correlation-Id", correlationId.toString())
                .body(body);
    }

    private static AdministrativeRoleResource resource(AdministrativeRole value) {
        List<PermissionResource> permissions = value.permissions().stream()
                .sorted(Comparator.comparing(AdministrativePermission::key))
                .map(p -> new PermissionResource(p.resourceType(), p.action(), p.key()))
                .toList();
        return new AdministrativeRoleResource(
                value.id(), value.code(), value.name(), permissions,
                value.revision(), value.createdAt(), value.updatedAt());
    }

    private static AdministrativeGrantResource resource(AdministrativeGrant value) {
        return new AdministrativeGrantResource(
                value.id(), value.actorIdentityId(), value.roleId(), resource(value.scope()), value.state().name(),
                value.validFrom(), value.validUntil(), value.grantable(), value.delegable(),
                value.authorityBasisGrantId(), value.revision(), value.createdAt(), value.updatedAt());
    }

    private static AdministrativeDelegationResource resource(AdministrativeDelegation value) {
        return new AdministrativeDelegationResource(
                value.id(), value.delegateIdentityId(), value.delegatorIdentityId(), value.sourceGrantId(),
                value.roleId(), resource(value.scope()), value.state().name(), value.validFrom(), value.validUntil(),
                value.createdByIdentityId(), value.revokedByIdentityId(), value.revokedAt(),
                value.correlationId(), value.causationId(), value.revision(), value.createdAt(), value.updatedAt());
    }

    private static AdministrativeElevationResource resource(AdministrativeElevation value) {
        return new AdministrativeElevationResource(
                value.id(), value.beneficiaryIdentityId(), value.initiatorIdentityId(), value.authorityBasisGrantId(),
                value.roleId(), resource(value.scope()), value.validFrom(), value.validUntil(), value.state().name(),
                value.approvalCaseId(), value.activatedAt(), value.deniedAt(), value.cancelledAt(),
                value.revokedAt(), value.correlationId(), value.causationId(), value.revision(),
                value.createdAt(), value.updatedAt());
    }

    private static AdministrativeBreakGlassResource resource(AdministrativeBreakGlassOperation value) {
        return new AdministrativeBreakGlassResource(
                value.id(), value.actorIdentityId(), value.roleId(), resource(value.scope()),
                value.validFrom(), value.validUntil(), value.state().name(), value.incidentReference(),
                value.revision(), value.createdAt(), value.updatedAt());
    }

    private static ScopeResource resource(AdministrativeScope value) {
        return new ScopeResource(value.type().name(), value.resourceType(), value.resourceId(), value.scopeKey());
    }

    private record RequestContext(AuthenticatedAdministrativeActor actor, UUID correlationId) {}
}
