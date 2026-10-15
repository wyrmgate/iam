package io.wyrmgate.iam.api.access;

import io.wyrmgate.iam.access.application.AccessAssignmentQueryService;
import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentFilter;
import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentPosition;
import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentValidity;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveDetail;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectivePosition;
import io.wyrmgate.iam.access.application.EffectiveAccessReadService;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetSearchQuery;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.application.RoleQueryService;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityListFilter;
import io.wyrmgate.iam.identity.application.IdentityQueryService;
import io.wyrmgate.iam.identity.application.PrincipalQueryService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bounded presentation composition for the Console Access workbench. It never owns or mutates
 * business state: authoritative mutations still use the Access API and all foreign labels/options
 * are read through their owning capability after independent Administrative Authorization.
 */
@RestController
@RequestMapping("/api/v1/console/access")
public final class AccessWorkbenchController {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_OPTION_LIMIT = 25;

    private final AccessAssignmentQueryService assignments;
    private final EffectiveAccessReadService effectiveAccess;
    private final IdentityQueryService identities;
    private final PrincipalQueryService principals;
    private final RoleQueryService roles;
    private final CatalogQueryService catalog;
    private final CatalogAccessTargetSearchQuery targetSearch;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final AccessCursorCodec cursors;

    public AccessWorkbenchController(
            AccessAssignmentQueryService assignments,
            EffectiveAccessReadService effectiveAccess,
            IdentityQueryService identities,
            PrincipalQueryService principals,
            RoleQueryService roles,
            CatalogQueryService catalog,
            CatalogAccessTargetSearchQuery targetSearch,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            AccessCursorCodec cursors) {
        this.assignments = assignments;
        this.effectiveAccess = effectiveAccess;
        this.identities = identities;
        this.principals = principals;
        this.roles = roles;
        this.catalog = catalog;
        this.targetSearch = targetSearch;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping("/assignments")
    public ResponseEntity<AssignmentPageResource> assignments(
            @RequestParam(required = false) UUID identityId,
            @RequestParam(required = false) String lifecycleState,
            @RequestParam(required = false) String targetKind,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) String provenance,
            @RequestParam(required = false) String validity,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant evaluatedAt = Instant.now();
        require(actor, AdministrativePermissions.ACCESS_ASSIGNMENT_READ,
                AdministrativeResource.collection("access-assignment"), evaluatedAt, correlationId);
        AssignmentFilter filter = filter(identityId, lifecycleState, targetKind, targetId, provenance, validity, correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        AssignmentPosition position = null;
        if (cursor != null) {
            try {
                position = cursors.decodeAssignments(cursor, actor.tenant(), filter);
            } catch (IllegalArgumentException invalid) {
                throw AccessApiException.validation(correlationId, "cursor", "invalid_cursor", "cursor is invalid or does not match the current filters.");
            }
        }
        var page = assignments.list(actor.tenant(), filter, position, effectiveLimit, evaluatedAt);
        List<AssignmentResource> items = page.items().stream()
                .map(item -> assignmentResource(actor, item, evaluatedAt))
                .toList();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(new AssignmentPageResource(
                        items,
                        cursors.encodeAssignments(actor.tenant(), filter, page.nextPosition()),
                        evaluatedAt));
    }

    @GetMapping("/assignments/{assignmentId}")
    public ResponseEntity<AssignmentResource> assignment(
            @PathVariable UUID assignmentId,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        require(actor, AdministrativePermissions.ACCESS_ASSIGNMENT_READ,
                new AdministrativeResource("access-assignment", assignmentId), now, correlationId);
        AccessAssignment value = assignments.find(actor.tenant(), assignmentId)
                .orElseThrow(() -> AccessApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"rev-" + value.revision() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(assignmentResource(actor, value, now));
    }

    @GetMapping("/effective")
    public ResponseEntity<EffectivePageResource> effective(
            @RequestParam(required = false) UUID identityId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        require(actor, AdministrativePermissions.EFFECTIVE_ACCESS_READ,
                AdministrativeResource.collection("effective-access"), now, correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        EffectivePosition position = null;
        if (cursor != null) {
            try {
                position = cursors.decodeEffective(cursor, actor.tenant(), identityId);
            } catch (IllegalArgumentException invalid) {
                throw AccessApiException.validation(correlationId, "cursor", "invalid_cursor", "cursor is invalid or does not match the current Identity filter.");
            }
        }
        var page = effectiveAccess.list(actor.tenant(), identityId, position, effectiveLimit, now);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(new EffectivePageResource(
                        page.items().stream().map(item -> effectiveResource(actor, item)).toList(),
                        cursors.encodeEffective(actor.tenant(), identityId, page.nextPosition())));
    }

    @GetMapping("/effective/{effectiveAccessId}")
    public ResponseEntity<EffectiveDetailResource> effectiveDetail(
            @PathVariable UUID effectiveAccessId,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        require(actor, AdministrativePermissions.EFFECTIVE_ACCESS_READ,
                new AdministrativeResource("effective-access", effectiveAccessId), now, correlationId);
        EffectiveDetail detail = effectiveAccess.find(actor.tenant(), effectiveAccessId, now)
                .orElseThrow(() -> AccessApiException.notFound(correlationId));
        EffectiveResource summary = effectiveResource(actor, detail.effectiveAccess());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(new EffectiveDetailResource(
                        summary,
                        detail.supports().stream().map(support -> new SupportResource(
                                support.accessAssignmentId(),
                                support.pathHash(),
                                support.pathDepth(),
                                support.roleVersionPath())).toList()));
    }

    @GetMapping("/identity-options")
    public ResponseEntity<OptionPage> identityOptions(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.IDENTITY_READ,
                AdministrativeResource.collection("identity"), Instant.now(), correlationId);
        int bounded = validateOptionLimit(limit, correlationId);
        String normalized = optionQuery(query, correlationId);
        var page = identities.listIdentities(
                actor.tenant(), new IdentityListFilter(normalized, null, null), null, bounded);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(new OptionPage(page.items().stream()
                        .map(identity -> new OptionResource(
                                identity.id(), identity.displayName(), identity.type().name(), identity.lifecycleState().name()))
                        .toList()));
    }

    @GetMapping("/target-options")
    public ResponseEntity<OptionPage> targetOptions(
            @RequestParam String targetKind,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        CatalogAccessTargetSearchQuery.TargetKind kind;
        try {
            kind = CatalogAccessTargetSearchQuery.TargetKind.valueOf(targetKind.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw AccessApiException.validation(correlationId, "targetKind", "invalid_enum", "targetKind must be ROLE or ENTITLEMENT.");
        }
        AdministrativePermission permission = kind == CatalogAccessTargetSearchQuery.TargetKind.ROLE
                ? AdministrativePermissions.ROLE_READ : AdministrativePermissions.ENTITLEMENT_READ;
        String resourceType = kind == CatalogAccessTargetSearchQuery.TargetKind.ROLE ? "role" : "entitlement";
        require(actor, permission, AdministrativeResource.collection(resourceType), Instant.now(), correlationId);
        int bounded = validateOptionLimit(limit, correlationId);
        String normalized = optionQuery(query, correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(new OptionPage(targetSearch.search(actor.tenant(), kind, normalized, bounded).stream()
                        .map(option -> new OptionResource(option.id(), option.label(), option.code(), option.lifecycleState()))
                        .toList()));
    }

    @GetMapping("/principal-options")
    public ResponseEntity<OptionPage> principalOptions(
            @RequestParam UUID identityId,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.PRINCIPAL_READ,
                AdministrativeResource.collection("principal"), Instant.now(), correlationId);
        int bounded = validateOptionLimit(limit, correlationId);
        var page = principals.list(actor.tenant(), identityId, null, bounded);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(new OptionPage(page.items().stream()
                        .map(principal -> new OptionResource(
                                principal.id(), principal.nativePrincipalKey(), principal.kind().name(), principal.lifecycleState().name()))
                        .toList()));
    }

    private AssignmentResource assignmentResource(
            AuthenticatedAdministrativeActor actor,
            AccessAssignment value,
            Instant evaluatedAt) {
        UUID targetId = value.targetKind() == AccessAssignment.TargetKind.ROLE ? value.roleId() : value.entitlementId();
        return new AssignmentResource(
                value.id(),
                value.identityId(),
                identityLabel(actor, value.identityId()),
                value.targetKind().name(),
                targetId,
                targetLabel(actor, value.targetKind(), targetId),
                value.principalConstraintKind().name(),
                value.specificPrincipalId(),
                principalLabel(actor, value.specificPrincipalId()),
                value.provenanceKind().name(),
                value.provenanceRefId(),
                value.lifecycleState().name(),
                validity(value, evaluatedAt).name(),
                evaluatedAt,
                value.validFrom(),
                value.validUntil(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private EffectiveResource effectiveResource(
            AuthenticatedAdministrativeActor actor,
            EffectiveAccess value) {
        return new EffectiveResource(
                value.id(),
                value.identityId(),
                identityLabel(actor, value.identityId()),
                value.entitlementId(),
                targetLabel(actor, AccessAssignment.TargetKind.ENTITLEMENT, value.entitlementId()),
                value.principalConstraintKey(),
                value.supportCount(),
                value.computedAt(),
                value.projectionGeneration());
    }

    private String identityLabel(AuthenticatedAdministrativeActor actor, UUID identityId) {
        Instant now = Instant.now();
        if (!canRead(actor, AdministrativePermissions.IDENTITY_READ, new AdministrativeResource("identity", identityId), now)) return null;
        return identities.findIdentity(actor.tenant(), identityId).map(identity -> identity.displayName()).orElse(null);
    }

    private String targetLabel(
            AuthenticatedAdministrativeActor actor,
            AccessAssignment.TargetKind targetKind,
            UUID targetId) {
        if (targetId == null) return null;
        Instant now = Instant.now();
        if (targetKind == AccessAssignment.TargetKind.ROLE) {
            if (!canRead(actor, AdministrativePermissions.ROLE_READ, new AdministrativeResource("role", targetId), now)) return null;
            return roles.findRole(actor.tenant(), targetId).map(role -> role.name()).orElse(null);
        }
        if (!canRead(actor, AdministrativePermissions.ENTITLEMENT_READ, new AdministrativeResource("entitlement", targetId), now)) return null;
        return catalog.findEntitlement(actor.tenant(), targetId).map(entitlement -> entitlement.code()).orElse(null);
    }

    private String principalLabel(AuthenticatedAdministrativeActor actor, UUID principalId) {
        if (principalId == null) return null;
        if (!canRead(actor, AdministrativePermissions.PRINCIPAL_READ,
                new AdministrativeResource("principal", principalId), Instant.now())) return null;
        return principals.findById(actor.tenant(), principalId).map(principal -> principal.nativePrincipalKey()).orElse(null);
    }

    private boolean canRead(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now) {
        return authorization.authorize(actor, permission, resource, now).allowed();
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        if (!canRead(actor, permission, resource, now)) throw AccessApiException.forbidden(correlationId);
    }

    private static AssignmentFilter filter(
            UUID identityId,
            String lifecycleState,
            String targetKind,
            UUID targetId,
            String provenance,
            String validity,
            UUID correlationId) {
        AccessAssignment.LifecycleState lifecycle = enumValue(
                lifecycleState, AccessAssignment.LifecycleState.class, "lifecycleState", correlationId);
        AccessAssignment.TargetKind target = enumValue(
                targetKind, AccessAssignment.TargetKind.class, "targetKind", correlationId);
        AccessAssignment.ProvenanceKind provenanceKind = enumValue(
                provenance, AccessAssignment.ProvenanceKind.class, "provenance", correlationId);
        AssignmentValidity temporal = enumValue(validity, AssignmentValidity.class, "validity", correlationId);
        if (targetId != null && target == null) {
            throw AccessApiException.validation(correlationId, "targetId", "missing_target_kind", "targetId requires targetKind.");
        }
        return new AssignmentFilter(identityId, lifecycle, target, targetId, provenanceKind, temporal);
    }

    private static <E extends Enum<E>> E enumValue(
            String raw, Class<E> type, String field, UUID correlationId) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw AccessApiException.validation(correlationId, field, "invalid_enum", field + " contains an unsupported value.");
        }
    }

    private static AssignmentValidity validity(AccessAssignment value, Instant now) {
        if (value.validFrom() != null && now.isBefore(value.validFrom())) return AssignmentValidity.FUTURE;
        if (value.validUntil() != null && !now.isBefore(value.validUntil())) return AssignmentValidity.ENDED;
        return AssignmentValidity.CURRENT;
    }

    private static int validateLimit(Integer limit, UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > 200) {
            throw AccessApiException.validation(correlationId, "limit", "out_of_range", "limit must be between 1 and 200.");
        }
        return value;
    }

    private static int validateOptionLimit(Integer limit, UUID correlationId) {
        int value = limit == null ? MAX_OPTION_LIMIT : limit;
        if (value < 1 || value > MAX_OPTION_LIMIT) {
            throw AccessApiException.validation(correlationId, "limit", "out_of_range", "option limit must be between 1 and 25.");
        }
        return value;
    }

    private static String optionQuery(String raw, UUID correlationId) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > 200 || value.indexOf('\u0000') >= 0) {
            throw AccessApiException.validation(correlationId, "query", "invalid_query", "query must contain no NUL and be at most 200 characters.");
        }
        return value;
    }

    public record AssignmentPageResource(
            List<AssignmentResource> items,
            String nextCursor,
            Instant evaluatedAt) {}

    public record AssignmentResource(
            UUID id,
            UUID identityId,
            String identityLabel,
            String targetKind,
            UUID targetId,
            String targetLabel,
            String principalConstraintKind,
            UUID specificPrincipalId,
            String principalLabel,
            String provenanceKind,
            UUID provenanceRefId,
            String lifecycleState,
            String validity,
            Instant validityEvaluatedAt,
            Instant validFrom,
            Instant validUntil,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    public record EffectivePageResource(List<EffectiveResource> items, String nextCursor) {}

    public record EffectiveResource(
            UUID id,
            UUID identityId,
            String identityLabel,
            UUID entitlementId,
            String entitlementLabel,
            String principalConstraintKey,
            int supportCount,
            Instant computedAt,
            long projectionGeneration) {}

    public record EffectiveDetailResource(
            EffectiveResource effective,
            List<SupportResource> supports) {}

    public record SupportResource(
            UUID accessAssignmentId,
            String pathHash,
            int pathDepth,
            List<UUID> roleVersionPath) {}

    public record OptionPage(List<OptionResource> items) {}
    public record OptionResource(UUID id, String label, String secondary, String state) {}
}
