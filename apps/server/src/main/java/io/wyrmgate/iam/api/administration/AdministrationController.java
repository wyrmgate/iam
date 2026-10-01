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
import io.wyrmgate.iam.api.administration.AdministrationApiModels.AdministrativeBreakGlassPage;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.AdministrativeBreakGlassResource;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.AdministrativeDelegationPage;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.AdministrativeDelegationResource;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.AdministrativeElevationPage;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.AdministrativeElevationResource;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.AdministrativeGrantPage;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.AdministrativeGrantResource;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.AdministrativeRolePage;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.AdministrativeRoleResource;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.ScopeResource;
import io.wyrmgate.iam.api.administration.AdministrationCursorCodec.PagePosition;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
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

    AdministrationController(
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
    ResponseEntity<AdministrativeRolePage> listRoles(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        RequestContext context = context(request);
        int pageSize = validateLimit(limit, context.correlationId());
        PagePosition position = decode(
                cursor,
                "administrative-role",
                context,
                context.correlationId());
        List<AdministrativeRole> values = authority.listRoles(
                context.actor(),
                position == null ? null : position.createdAt(),
                position == null ? null : position.id(),
                pageSize,
                Instant.now());
        return ok(new AdministrativeRolePage(
                values.stream().map(AdministrationController::roleResource).toList(),
                nextCursor(
                        "administrative-role",
                        context.actor(),
                        values,
                        pageSize,
                        value -> new PagePosition(value.createdAt(), value.id()))),
                context.correlationId());
    }

    @PostMapping("/administrative-roles")
    ResponseEntity<AdministrativeRoleResource> createRole(
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext context = context(request);
        String key = validateIdempotencyKey(
                idempotencyKey, context.correlationId());
        Map<String,Object> parsed = exact(
                body,
                Set.of("code", "name", "permissions"),
                context.correlationId());
        String code = requiredString(
                parsed.get("code"), "code", context.correlationId());
        String name = requiredString(
                parsed.get("name"), "name", context.correlationId());
        Set<AdministrativePermission> permissions = permissions(
                parsed.get("permissions"),
                context.correlationId());
        AdministrativeRole value = mutations.createRole(
                context.actor(),
                code,
                name,
                permissions,
                key,
                fingerprint(
                        "role:create",
                        code,
                        name,
                        permissionFingerprint(permissions)),
                Instant.now(),
                context.correlationId(),
                context.causationId());
        return created(
                roleResource(value),
                "/api/v1/administrative-roles/" + value.id(),
                value.revision(),
                context.correlationId());
    }

    @GetMapping("/administrative-roles/{roleId}")
    ResponseEntity<AdministrativeRoleResource> getRole(
            @PathVariable UUID roleId,
            HttpServletRequest request) {
        RequestContext context = context(request);
        AdministrativeRole value =
                authority.getRole(context.actor(), roleId, Instant.now());
        return okWithEtag(
                roleResource(value),
                value.revision(),
                context.correlationId());
    }

    @PostMapping("/administrative-roles/{roleId}/rename")
    ResponseEntity<AdministrativeRoleResource> renameRole(
            @PathVariable UUID roleId,
            @RequestHeader(name = "If-Match", required = false)
                    String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext context = context(request);
        long revision = parseIfMatch(
                ifMatch, context.correlationId());
        String key = validateIdempotencyKey(
                idempotencyKey, context.correlationId());
        Map<String,Object> parsed = exact(
                body, Set.of("name"), context.correlationId());
        String name = requiredString(
                parsed.get("name"), "name", context.correlationId());
        AdministrativeRole value = mutations.renameRole(
                context.actor(),
                roleId,
                name,
                revision,
                key,
                fingerprint("role:rename", roleId, revision, name),
                Instant.now(),
                context.correlationId(),
                context.causationId());
        return okWithEtag(
                roleResource(value),
                value.revision(),
                context.correlationId());
    }

    @PostMapping("/administrative-roles/{roleId}/permissions/add")
    ResponseEntity<AdministrativeRoleResource> addRolePermission(
            @PathVariable UUID roleId,
            @RequestHeader(name = "If-Match", required = false)
                    String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        return rolePermissionMutation(
                roleId,
                true,
                ifMatch,
                idempotencyKey,
                body,
                request);
    }

    @PostMapping("/administrative-roles/{roleId}/permissions/remove")
    ResponseEntity<AdministrativeRoleResource> removeRolePermission(
            @PathVariable UUID roleId,
            @RequestHeader(name = "If-Match", required = false)
                    String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        return rolePermissionMutation(
                roleId,
                false,
                ifMatch,
                idempotencyKey,
                body,
                request);
    }

    @GetMapping("/administrative-grants")
    ResponseEntity<AdministrativeGrantPage> listGrants(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        RequestContext context = context(request);
        int pageSize = validateLimit(limit, context.correlationId());
        PagePosition position = decode(
                cursor,
                "administrative-grant",
                context,
                context.correlationId());
        List<AdministrativeGrant> values = authority.listGrants(
                context.actor(),
                position == null ? null : position.createdAt(),
                position == null ? null : position.id(),
                pageSize,
                Instant.now());
        return ok(new AdministrativeGrantPage(
                values.stream().map(AdministrationController::grantResource).toList(),
                nextCursor(
                        "administrative-grant",
                        context.actor(),
                        values,
                        pageSize,
                        value -> new PagePosition(value.createdAt(), value.id()))),
                context.correlationId());
    }

    @PostMapping("/administrative-grants")
    ResponseEntity<AdministrativeGrantResource> createGrant(
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext context = context(request);
        String key = validateIdempotencyKey(
                idempotencyKey, context.correlationId());
        Map<String,Object> parsed = exact(
                body,
                Set.of(
                        "beneficiaryIdentityId",
                        "roleId",
                        "scope",
                        "validFrom",
                        "validUntil",
                        "grantable",
                        "delegable",
                        "authorityBasisGrantId"),
                context.correlationId());
        UUID beneficiary = requiredUuid(
                parsed.get("beneficiaryIdentityId"),
                "beneficiaryIdentityId",
                context.correlationId());
        UUID roleId = requiredUuid(
                parsed.get("roleId"), "roleId", context.correlationId());
        AdministrativeScope scope = scope(
                parsed.get("scope"), context.correlationId());
        Instant validFrom = optionalInstant(
                parsed.get("validFrom"),
                "validFrom",
                context.correlationId());
        Instant validUntil = optionalInstant(
                parsed.get("validUntil"),
                "validUntil",
                context.correlationId());
        boolean grantable = requiredBoolean(
                parsed.get("grantable"),
                "grantable",
                context.correlationId());
        boolean delegable = requiredBoolean(
                parsed.get("delegable"),
                "delegable",
                context.correlationId());
        UUID basisId = requiredUuid(
                parsed.get("authorityBasisGrantId"),
                "authorityBasisGrantId",
                context.correlationId());

        AdministrativeGrant value = mutations.createGrant(
                context.actor(),
                beneficiary,
                roleId,
                scope,
                validFrom,
                validUntil,
                grantable,
                delegable,
                basisId,
                key,
                fingerprint(
                        "grant:create",
                        beneficiary,
                        roleId,
                        scopeFingerprint(scope),
                        validFrom,
                        validUntil,
                        grantable,
                        delegable,
                        basisId),
                Instant.now(),
                context.correlationId(),
                context.causationId());
        return created(
                grantResource(value),
                "/api/v1/administrative-grants/" + value.id(),
                value.revision(),
                context.correlationId());
    }

    @GetMapping("/administrative-grants/{grantId}")
    ResponseEntity<AdministrativeGrantResource> getGrant(
            @PathVariable UUID grantId,
            HttpServletRequest request) {
        RequestContext context = context(request);
        AdministrativeGrant value =
                authority.getGrant(context.actor(), grantId, Instant.now());
        return okWithEtag(
                grantResource(value),
                value.revision(),
                context.correlationId());
    }

    @PostMapping("/administrative-grants/{grantId}/revoke")
    ResponseEntity<AdministrativeGrantResource> revokeGrant(
            @PathVariable UUID grantId,
            @RequestHeader(name = "If-Match", required = false)
                    String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        RequestContext context = context(request);
        long revision = parseIfMatch(ifMatch, context.correlationId());
        String key = validateIdempotencyKey(
                idempotencyKey, context.correlationId());
        AdministrativeGrant value = mutations.revokeGrant(
                context.actor(),
                grantId,
                revision,
                key,
                fingerprint("grant:revoke", grantId, revision),
                Instant.now(),
                context.correlationId(),
                context.causationId());
        return okWithEtag(
                grantResource(value),
                value.revision(),
                context.correlationId());
    }

    @GetMapping("/administrative-delegations")
    ResponseEntity<AdministrativeDelegationPage> listDelegations(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        RequestContext context = context(request);
        int pageSize = validateLimit(limit, context.correlationId());
        PagePosition position = decode(
                cursor,
                "administrative-delegation",
                context,
                context.correlationId());
        List<AdministrativeDelegation> values =
                authority.listDelegations(
                        context.actor(),
                        position == null ? null : position.createdAt(),
                        position == null ? null : position.id(),
                        pageSize,
                        Instant.now());
        return ok(new AdministrativeDelegationPage(
                values.stream()
                        .map(AdministrationController::delegationResource)
                        .toList(),
                nextCursor(
                        "administrative-delegation",
                        context.actor(),
                        values,
                        pageSize,
                        value -> new PagePosition(value.createdAt(), value.id()))),
                context.correlationId());
    }

    @PostMapping("/administrative-delegations")
    ResponseEntity<AdministrativeDelegationResource> createDelegation(
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext context = context(request);
        String key = validateIdempotencyKey(
                idempotencyKey, context.correlationId());
        Map<String,Object> parsed = exact(
                body,
                Set.of(
                        "delegateIdentityId",
                        "sourceGrantId",
                        "scope",
                        "validFrom",
                        "validUntil"),
                context.correlationId());
        UUID delegateId = requiredUuid(
                parsed.get("delegateIdentityId"),
                "delegateIdentityId",
                context.correlationId());
        UUID sourceGrantId = requiredUuid(
                parsed.get("sourceGrantId"),
                "sourceGrantId",
                context.correlationId());
        AdministrativeScope scope = scope(
                parsed.get("scope"), context.correlationId());
        Instant validFrom = optionalInstant(
                parsed.get("validFrom"),
                "validFrom",
                context.correlationId());
        Instant validUntil = requiredInstant(
                parsed.get("validUntil"),
                "validUntil",
                context.correlationId());

        AdministrativeDelegation value =
                mutations.createDelegation(
                        context.actor(),
                        delegateId,
                        sourceGrantId,
                        scope,
                        validFrom,
                        validUntil,
                        key,
                        fingerprint(
                                "delegation:create",
                                delegateId,
                                sourceGrantId,
                                scopeFingerprint(scope),
                                validFrom,
                                validUntil),
                        Instant.now(),
                        context.correlationId(),
                        context.causationId());
        return created(
                delegationResource(value),
                "/api/v1/administrative-delegations/" + value.id(),
                value.revision(),
                context.correlationId());
    }

    @GetMapping("/administrative-delegations/{delegationId}")
    ResponseEntity<AdministrativeDelegationResource> getDelegation(
            @PathVariable UUID delegationId,
            HttpServletRequest request) {
        RequestContext context = context(request);
        AdministrativeDelegation value =
                authority.getDelegation(
                        context.actor(), delegationId, Instant.now());
        return okWithEtag(
                delegationResource(value),
                value.revision(),
                context.correlationId());
    }

    @PostMapping("/administrative-delegations/{delegationId}/revoke")
    ResponseEntity<AdministrativeDelegationResource> revokeDelegation(
            @PathVariable UUID delegationId,
            @RequestHeader(name = "If-Match", required = false)
                    String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        RequestContext context = context(request);
        long revision = parseIfMatch(ifMatch, context.correlationId());
        String key = validateIdempotencyKey(
                idempotencyKey, context.correlationId());
        AdministrativeDelegation value =
                mutations.revokeDelegation(
                        context.actor(),
                        delegationId,
                        revision,
                        key,
                        fingerprint(
                                "delegation:revoke",
                                delegationId,
                                revision),
                        Instant.now(),
                        context.correlationId(),
                        context.causationId());
        return okWithEtag(
                delegationResource(value),
                value.revision(),
                context.correlationId());
    }

    @GetMapping("/administrative-elevations")
    ResponseEntity<AdministrativeElevationPage> listElevations(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        RequestContext context = context(request);
        int pageSize = validateLimit(limit, context.correlationId());
        PagePosition position = decode(
                cursor,
                "administrative-elevation",
                context,
                context.correlationId());
        List<AdministrativeElevation> values = elevations.list(
                context.actor(),
                position == null ? null : position.createdAt(),
                position == null ? null : position.id(),
                pageSize,
                Instant.now());
        return ok(new AdministrativeElevationPage(
                values.stream()
                        .map(AdministrationController::elevationResource)
                        .toList(),
                nextCursor(
                        "administrative-elevation",
                        context.actor(),
                        values,
                        pageSize,
                        value -> new PagePosition(value.createdAt(), value.id()))),
                context.correlationId());
    }

    @PostMapping("/administrative-elevations")
    ResponseEntity<AdministrativeElevationResource> requestElevation(
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext context = context(request);
        String key = validateIdempotencyKey(
                idempotencyKey, context.correlationId());
        Map<String,Object> parsed = exact(
                body,
                Set.of(
                        "beneficiaryIdentityId",
                        "roleId",
                        "scope",
                        "validFrom",
                        "validUntil",
                        "authorityBasisGrantId"),
                context.correlationId());
        UUID beneficiaryId = requiredUuid(
                parsed.get("beneficiaryIdentityId"),
                "beneficiaryIdentityId",
                context.correlationId());
        UUID roleId = requiredUuid(
                parsed.get("roleId"),
                "roleId",
                context.correlationId());
        AdministrativeScope scope = scope(
                parsed.get("scope"), context.correlationId());
        Instant validFrom = optionalInstant(
                parsed.get("validFrom"),
                "validFrom",
                context.correlationId());
        Instant validUntil = requiredInstant(
                parsed.get("validUntil"),
                "validUntil",
                context.correlationId());
        UUID basisId = requiredUuid(
                parsed.get("authorityBasisGrantId"),
                "authorityBasisGrantId",
                context.correlationId());

        AdministrativeElevation value =
                mutations.requestElevation(
                        context.actor(),
                        beneficiaryId,
                        roleId,
                        scope,
                        validFrom,
                        validUntil,
                        basisId,
                        key,
                        fingerprint(
                                "elevation:request",
                                beneficiaryId,
                                roleId,
                                scopeFingerprint(scope),
                                validFrom,
                                validUntil,
                                basisId),
                        Instant.now(),
                        context.correlationId(),
                        context.causationId());
        return created(
                elevationResource(value),
                "/api/v1/administrative-elevations/" + value.id(),
                value.revision(),
                context.correlationId());
    }

    @GetMapping("/administrative-elevations/{elevationId}")
    ResponseEntity<AdministrativeElevationResource> getElevation(
            @PathVariable UUID elevationId,
            HttpServletRequest request) {
        RequestContext context = context(request);
        AdministrativeElevation value =
                elevations.get(
                        context.actor(), elevationId, Instant.now());
        return okWithEtag(
                elevationResource(value),
                value.revision(),
                context.correlationId());
    }

    @PostMapping("/administrative-elevations/{elevationId}/request-approval")
    ResponseEntity<AdministrativeElevationResource> requestElevationApproval(
            @PathVariable UUID elevationId,
            @RequestHeader(name = "If-Match", required = false)
                    String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        return elevationAction(
                elevationId,
                "request-approval",
                ifMatch,
                idempotencyKey,
                request);
    }

    @PostMapping("/administrative-elevations/{elevationId}/apply")
    ResponseEntity<AdministrativeElevationResource> applyElevation(
            @PathVariable UUID elevationId,
            @RequestHeader(name = "If-Match", required = false)
                    String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        return elevationAction(
                elevationId,
                "apply",
                ifMatch,
                idempotencyKey,
                request);
    }

    @PostMapping("/administrative-elevations/{elevationId}/cancel")
    ResponseEntity<AdministrativeElevationResource> cancelElevation(
            @PathVariable UUID elevationId,
            @RequestHeader(name = "If-Match", required = false)
                    String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        return elevationAction(
                elevationId,
                "cancel",
                ifMatch,
                idempotencyKey,
                request);
    }

    @PostMapping("/administrative-elevations/{elevationId}/revoke")
    ResponseEntity<AdministrativeElevationResource> revokeElevation(
            @PathVariable UUID elevationId,
            @RequestHeader(name = "If-Match", required = false)
                    String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        return elevationAction(
                elevationId,
                "revoke",
                ifMatch,
                idempotencyKey,
                request);
    }

    @GetMapping("/administrative-break-glass-operations")
    ResponseEntity<AdministrativeBreakGlassPage> listBreakGlass(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        RequestContext context = context(request);
        int pageSize = validateLimit(limit, context.correlationId());
        PagePosition position = decode(
                cursor,
                "administrative-break-glass-operation",
                context,
                context.correlationId());
        List<AdministrativeBreakGlassOperation> values =
                breakGlass.list(
                        context.actor(),
                        position == null ? null : position.createdAt(),
                        position == null ? null : position.id(),
                        pageSize,
                        Instant.now());
        return ok(new AdministrativeBreakGlassPage(
                values.stream()
                        .map(AdministrationController::breakGlassResource)
                        .toList(),
                nextCursor(
                        "administrative-break-glass-operation",
                        context.actor(),
                        values,
                        pageSize,
                        value -> new PagePosition(value.createdAt(), value.id()))),
                context.correlationId());
    }

    @PostMapping("/administrative-break-glass-operations")
    ResponseEntity<AdministrativeBreakGlassResource> activateBreakGlass(
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @RequestBody Map<String,Object> body,
            HttpServletRequest request) {
        RequestContext context = context(request);
        String key = validateIdempotencyKey(
                idempotencyKey, context.correlationId());
        Map<String,Object> parsed = exact(
                body,
                Set.of(
                        "roleId",
                        "scope",
                        "validUntil",
                        "reason",
                        "incidentReference"),
                context.correlationId());
        UUID roleId = requiredUuid(
                parsed.get("roleId"),
                "roleId",
                context.correlationId());
        AdministrativeScope scope = scope(
                parsed.get("scope"), context.correlationId());
        Instant validUntil = requiredInstant(
                parsed.get("validUntil"),
                "validUntil",
                context.correlationId());
        String reason = requiredString(
                parsed.get("reason"),
                "reason",
                context.correlationId());
        String incident = requiredString(
                parsed.get("incidentReference"),
                "incidentReference",
                context.correlationId());

        AdministrativeBreakGlassOperation value =
                mutations.activateBreakGlass(
                        context.actor(),
                        roleId,
                        scope,
                        validUntil,
                        reason,
                        incident,
                        key,
                        fingerprint(
                                "break-glass:activate",
                                roleId,
                                scopeFingerprint(scope),
                                validUntil,
                                reason,
                                incident),
                        Instant.now(),
                        context.correlationId(),
                        context.causationId());
        return created(
                breakGlassResource(value),
                "/api/v1/administrative-break-glass-operations/"
                        + value.id(),
                value.revision(),
                context.correlationId());
    }

    @GetMapping("/administrative-break-glass-operations/{operationId}")
    ResponseEntity<AdministrativeBreakGlassResource> getBreakGlass(
            @PathVariable UUID operationId,
            HttpServletRequest request) {
        RequestContext context = context(request);
        AdministrativeBreakGlassOperation value =
                breakGlass.get(
                        context.actor(), operationId, Instant.now());
        return okWithEtag(
                breakGlassResource(value),
                value.revision(),
                context.correlationId());
    }

    @PostMapping("/administrative-break-glass-operations/{operationId}/revoke")
    ResponseEntity<AdministrativeBreakGlassResource> revokeBreakGlass(
            @PathVariable UUID operationId,
            @RequestHeader(name = "If-Match", required = false)
                    String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            HttpServletRequest request) {
        RequestContext context = context(request);
        long revision = parseIfMatch(
                ifMatch, context.correlationId());
        String key = validateIdempotencyKey(
                idempotencyKey, context.correlationId());
        AdministrativeBreakGlassOperation value =
                mutations.revokeBreakGlass(
                        context.actor(),
                        operationId,
                        revision,
                        key,
                        fingerprint(
                                "break-glass:revoke",
                                operationId,
                                revision),
                        Instant.now(),
                        context.correlationId(),
                        context.causationId());
        return okWithEtag(
                breakGlassResource(value),
                value.revision(),
                context.correlationId());
    }

    private ResponseEntity<AdministrativeRoleResource>
            rolePermissionMutation(
                    UUID roleId,
                    boolean add,
                    String ifMatch,
                    String idempotencyKey,
                    Map<String,Object> body,
                    HttpServletRequest request) {
        RequestContext context = context(request);
        long revision = parseIfMatch(
                ifMatch, context.correlationId());
        String key = validateIdempotencyKey(
                idempotencyKey, context.correlationId());
        Map<String,Object> parsed = exact(
                body, Set.of("permission"), context.correlationId());
        AdministrativePermission permission = permission(
                parsed.get("permission"),
                "permission",
                context.correlationId());
        RequestFingerprint fingerprint = fingerprint(
                add ? "role:permission:add" : "role:permission:remove",
                roleId,
                revision,
                permission.key());
        AdministrativeRole value = add
                ? mutations.addRolePermission(
                        context.actor(),
                        roleId,
                        permission,
                        revision,
                        key,
                        fingerprint,
                        Instant.now(),
                        context.correlationId(),
                        context.causationId())
                : mutations.removeRolePermission(
                        context.actor(),
                        roleId,
                        permission,
                        revision,
                        key,
                        fingerprint,
                        Instant.now(),
                        context.correlationId(),
                        context.causationId());
        return okWithEtag(
                roleResource(value),
                value.revision(),
                context.correlationId());
    }

    private ResponseEntity<AdministrativeElevationResource>
            elevationAction(
                    UUID elevationId,
                    String action,
                    String ifMatch,
                    String idempotencyKey,
                    HttpServletRequest request) {
        RequestContext context = context(request);
        long revision = parseIfMatch(
                ifMatch, context.correlationId());
        String key = validateIdempotencyKey(
                idempotencyKey, context.correlationId());
        RequestFingerprint fingerprint = fingerprint(
                "elevation:" + action,
                elevationId,
                revision);
        Instant now = Instant.now();
        AdministrativeElevation value = switch (action) {
            case "request-approval" ->
                    mutations.requestElevationApproval(
                            context.actor(),
                            elevationId,
                            revision,
                            key,
                            fingerprint,
                            now,
                            context.correlationId(),
                            context.causationId());
            case "apply" ->
                    mutations.applyElevation(
                            context.actor(),
                            elevationId,
                            revision,
                            key,
                            fingerprint,
                            now,
                            context.correlationId(),
                            context.causationId());
            case "cancel" ->
                    mutations.cancelElevation(
                            context.actor(),
                            elevationId,
                            revision,
                            key,
                            fingerprint,
                            now,
                            context.correlationId(),
                            context.causationId());
            case "revoke" ->
                    mutations.revokeElevation(
                            context.actor(),
                            elevationId,
                            revision,
                            key,
                            fingerprint,
                            now,
                            context.correlationId(),
                            context.causationId());
            default -> throw new IllegalStateException(
                    "Unsupported elevation action");
        };
        return okWithEtag(
                elevationResource(value),
                value.revision(),
                context.correlationId());
    }

    private RequestContext context(HttpServletRequest request) {
        UUID correlationId =
                AdministrationApiRequestContext.resolveCorrelationId(
                        request, ids);
        return new RequestContext(
                ControlPlaneActorRequestContext.require(request),
                correlationId,
                AdministrationApiRequestContext.optionalCausationId(
                        request, correlationId));
    }

    private PagePosition decode(
            String cursor,
            String resourceKind,
            RequestContext context,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decode(
                    cursor, resourceKind, context.actor().tenant());
        } catch (IllegalArgumentException invalid) {
            throw AdministrationApiException.validation(
                    correlationId,
                    "cursor",
                    "invalid_cursor",
                    "cursor is invalid or does not match this collection.");
        }
    }

    private <T> String nextCursor(
            String resourceKind,
            AuthenticatedAdministrativeActor actor,
            List<T> values,
            int limit,
            java.util.function.Function<T, PagePosition> position) {
        if (values.isEmpty() || values.size() < limit) return null;
        return cursors.encode(
                resourceKind,
                actor.tenant(),
                position.apply(values.get(values.size() - 1)));
    }

    private static int validateLimit(
            Integer limit,
            UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > MAX_LIMIT) {
            throw AdministrationApiException.validation(
                    correlationId,
                    "limit",
                    "out_of_range",
                    "limit must be between 1 and 200.");
        }
        return value;
    }

    private static String validateIdempotencyKey(
            String key,
            UUID correlationId) {
        if (key == null
                || key.isBlank()
                || key.length() < 8
                || key.length() > 200) {
            throw AdministrationApiException.validation(
                    correlationId,
                    "Idempotency-Key",
                    "invalid_length",
                    "Idempotency-Key must contain between 8 and 200 characters.");
        }
        return key;
    }

    private static long parseIfMatch(
            String value,
            UUID correlationId) {
        if (value == null
                || !value.startsWith("\"rev-")
                || !value.endsWith("\"")) {
            throw AdministrationApiException.validation(
                    correlationId,
                    "If-Match",
                    "invalid_etag",
                    "If-Match must contain a strong revision ETag.");
        }
        try {
            long revision = Long.parseLong(
                    value.substring(5, value.length() - 1));
            if (revision < 1) throw new NumberFormatException();
            return revision;
        } catch (NumberFormatException invalid) {
            throw AdministrationApiException.validation(
                    correlationId,
                    "If-Match",
                    "invalid_etag",
                    "If-Match revision is outside the supported range.");
        }
    }

    private static Map<String,Object> exact(
            Map<String,Object> body,
            Set<String> fields,
            UUID correlationId) {
        if (body == null || !body.keySet().equals(fields)) {
            throw AdministrationApiException.validation(
                    correlationId,
                    "request",
                    "unexpected_fields",
                    "Request body fields do not match the operation contract.");
        }
        return new LinkedHashMap<>(body);
    }

    private static String requiredString(
            Object value,
            String field,
            UUID correlationId) {
        if (!(value instanceof String text)
                || text.isBlank()) {
            throw AdministrationApiException.validation(
                    correlationId,
                    field,
                    "required",
                    field + " must be a non-blank string.");
        }
        return text.trim();
    }

    private static boolean requiredBoolean(
            Object value,
            String field,
            UUID correlationId) {
        if (!(value instanceof Boolean flag)) {
            throw AdministrationApiException.validation(
                    correlationId,
                    field,
                    "invalid_boolean",
                    field + " must be a boolean.");
        }
        return flag;
    }

    private static UUID requiredUuid(
            Object value,
            String field,
            UUID correlationId) {
        UUID parsed = optionalUuid(
                value, field, correlationId);
        if (parsed == null) {
            throw AdministrationApiException.validation(
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
            throw AdministrationApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string or null.");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw AdministrationApiException.validation(
                    correlationId,
                    field,
                    "invalid_uuid",
                    field + " must be a UUID string or null.");
        }
    }

    private static Instant requiredInstant(
            Object value,
            String field,
            UUID correlationId) {
        Instant parsed = optionalInstant(
                value, field, correlationId);
        if (parsed == null) {
            throw AdministrationApiException.validation(
                    correlationId,
                    field,
                    "required",
                    field + " is required.");
        }
        return parsed;
    }

    private static Instant optionalInstant(
            Object value,
            String field,
            UUID correlationId) {
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw AdministrationApiException.validation(
                    correlationId,
                    field,
                    "invalid_datetime",
                    field + " must be an RFC3339 instant or null.");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            throw AdministrationApiException.validation(
                    correlationId,
                    field,
                    "invalid_datetime",
                    field + " must be an RFC3339 instant or null.");
        }
    }

    private static AdministrativePermission permission(
            Object value,
            String field,
            UUID correlationId) {
        String key = requiredString(
                value, field, correlationId);
        String[] parts = key.split(":", -1);
        if (parts.length != 2) {
            throw AdministrationApiException.validation(
                    correlationId,
                    field,
                    "invalid_permission",
                    "Administrative permission must use resource:action form.");
        }
        try {
            return new AdministrativePermission(parts[0], parts[1]);
        } catch (IllegalArgumentException invalid) {
            throw AdministrationApiException.validation(
                    correlationId,
                    field,
                    "invalid_permission",
                    "Administrative permission must use resource:action form.");
        }
    }

    private static Set<AdministrativePermission> permissions(
            Object value,
            UUID correlationId) {
        if (!(value instanceof List<?> list)
                || list.isEmpty()) {
            throw AdministrationApiException.validation(
                    correlationId,
                    "permissions",
                    "invalid_permissions",
                    "permissions must be a non-empty array.");
        }
        Set<AdministrativePermission> result = new LinkedHashSet<>();
        for (Object item : list) {
            AdministrativePermission permission = permission(
                    item, "permissions", correlationId);
            if (!result.add(permission)) {
                throw AdministrationApiException.validation(
                        correlationId,
                        "permissions",
                        "duplicate_permission",
                        "permissions must not contain duplicates.");
            }
        }
        return Set.copyOf(result);
    }

    private static AdministrativeScope scope(
            Object value,
            UUID correlationId) {
        if (!(value instanceof Map<?,?> raw)) {
            throw AdministrationApiException.validation(
                    correlationId,
                    "scope",
                    "invalid_scope",
                    "scope must be an object.");
        }
        Map<String,Object> map = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw AdministrationApiException.validation(
                        correlationId,
                        "scope",
                        "invalid_scope",
                        "scope keys must be strings.");
            }
            map.put(key, entry.getValue());
        }
        if (!map.keySet().equals(
                Set.of(
                        "type",
                        "resourceType",
                        "resourceId",
                        "scopeKey"))) {
            throw AdministrationApiException.validation(
                    correlationId,
                    "scope",
                    "invalid_scope",
                    "scope fields must be type, resourceType, resourceId, and scopeKey.");
        }
        String typeText = requiredString(
                map.get("type"),
                "scope.type",
                correlationId);
        AdministrativeScopeType type;
        try {
            type = AdministrativeScopeType.valueOf(typeText);
        } catch (IllegalArgumentException invalid) {
            throw AdministrationApiException.validation(
                    correlationId,
                    "scope.type",
                    "invalid_enum",
                    "scope.type is not a supported AdministrativeScope type.");
        }
        String resourceType = optionalString(
                map.get("resourceType"),
                "scope.resourceType",
                correlationId);
        UUID resourceId = optionalUuid(
                map.get("resourceId"),
                "scope.resourceId",
                correlationId);
        String scopeKey = optionalString(
                map.get("scopeKey"),
                "scope.scopeKey",
                correlationId);
        try {
            return new AdministrativeScope(
                    type,
                    resourceType,
                    resourceId,
                    scopeKey);
        } catch (IllegalArgumentException invalid) {
            throw AdministrationApiException.validation(
                    correlationId,
                    "scope",
                    "invalid_scope",
                    "scope fields do not match the selected scope type.");
        }
    }

    private static String optionalString(
            Object value,
            String field,
            UUID correlationId) {
        if (value == null) return null;
        if (!(value instanceof String text)
                || text.isBlank()) {
            throw AdministrationApiException.validation(
                    correlationId,
                    field,
                    "invalid_string",
                    field + " must be a non-blank string or null.");
        }
        return text.trim();
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
                normalized.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String permissionFingerprint(
            Set<AdministrativePermission> permissions) {
        return permissions.stream()
                .map(AdministrativePermission::key)
                .sorted()
                .reduce("", (a, b) -> a + b + ",");
    }

    private static String scopeFingerprint(
            AdministrativeScope scope) {
        return scope.type()
                + "|"
                + scope.resourceType()
                + "|"
                + scope.resourceId()
                + "|"
                + scope.scopeKey();
    }

    private static String etag(long revision) {
        return "\"rev-" + revision + "\"";
    }

    private static AdministrativeRoleResource roleResource(
            AdministrativeRole value) {
        List<String> permissions = new ArrayList<>(
                value.permissions().stream()
                        .map(AdministrativePermission::key)
                        .toList());
        permissions.sort(Comparator.naturalOrder());
        return new AdministrativeRoleResource(
                value.id(),
                value.code(),
                value.name(),
                List.copyOf(permissions),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private static AdministrativeGrantResource grantResource(
            AdministrativeGrant value) {
        return new AdministrativeGrantResource(
                value.id(),
                value.actorIdentityId(),
                value.roleId(),
                scopeResource(value.scope()),
                value.state().name(),
                value.validFrom(),
                value.validUntil(),
                value.grantable(),
                value.delegable(),
                value.authorityBasisGrantId(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private static AdministrativeDelegationResource delegationResource(
            AdministrativeDelegation value) {
        return new AdministrativeDelegationResource(
                value.id(),
                value.delegateIdentityId(),
                value.delegatorIdentityId(),
                value.sourceGrantId(),
                value.roleId(),
                scopeResource(value.scope()),
                value.state().name(),
                value.validFrom(),
                value.validUntil(),
                value.createdByIdentityId(),
                value.revokedByIdentityId(),
                value.revokedAt(),
                value.correlationId(),
                value.causationId(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private static AdministrativeElevationResource elevationResource(
            AdministrativeElevation value) {
        return new AdministrativeElevationResource(
                value.id(),
                value.beneficiaryIdentityId(),
                value.initiatorIdentityId(),
                value.authorityBasisGrantId(),
                value.roleId(),
                scopeResource(value.scope()),
                value.validFrom(),
                value.validUntil(),
                value.state().name(),
                value.approvalCaseId(),
                value.activatedAt(),
                value.deniedAt(),
                value.cancelledAt(),
                value.revokedAt(),
                value.correlationId(),
                value.causationId(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private static AdministrativeBreakGlassResource breakGlassResource(
            AdministrativeBreakGlassOperation value) {
        return new AdministrativeBreakGlassResource(
                value.id(),
                value.actorIdentityId(),
                value.roleId(),
                scopeResource(value.scope()),
                value.reason(),
                value.incidentReference(),
                value.validFrom(),
                value.validUntil(),
                value.activationAssuranceLevel().name(),
                value.activationAuthenticatedAt(),
                value.activationStepUpAt(),
                value.state().name(),
                value.activatedAt(),
                value.revokedByIdentityId(),
                value.revokedAt(),
                value.correlationId(),
                value.causationId(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private static ScopeResource scopeResource(
            AdministrativeScope scope) {
        return new ScopeResource(
                scope.type().name(),
                scope.resourceType(),
                scope.resourceId(),
                scope.scopeKey());
    }

    private static <T> ResponseEntity<T> created(
            T body,
            String location,
            long revision,
            UUID correlationId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, etag(revision))
                .header(HttpHeaders.LOCATION, location)
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static <T> ResponseEntity<T> okWithEtag(
            T body,
            long revision,
            UUID correlationId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag(revision))
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static <T> ResponseEntity<T> ok(
            T body,
            UUID correlationId) {
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private record RequestContext(
            AuthenticatedAdministrativeActor actor,
            UUID correlationId,
            UUID causationId) {}
}
