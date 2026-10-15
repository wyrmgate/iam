package io.wyrmgate.iam.api.access;

import io.wyrmgate.iam.access.application.AccessAssignmentQueryService;
import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentFilter;
import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentPosition;
import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentValidity;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.catalog.application.CatalogAccessPresentationQuery;
import io.wyrmgate.iam.identity.application.IdentityAccessPresentationQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Access-owned bounded operator read model for the Console.
 *
 * <p>The view does not introduce a second authority. Assignment fields come from Access authoritative
 * state; human labels are composed through owning-capability semantic queries and are omitted when
 * the caller lacks the corresponding foreign read authority.</p>
 */
@RestController
@RequestMapping("/api/v1/access-assignment-views")
public final class AccessAssignmentViewController {
    private static final int DEFAULT_LIMIT = 50;

    private final AccessAssignmentQueryService assignments;
    private final IdentityAccessPresentationQuery identityPresentation;
    private final CatalogAccessPresentationQuery catalogPresentation;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final AccessCursorCodec cursors;

    public AccessAssignmentViewController(
            AccessAssignmentQueryService assignments,
            IdentityAccessPresentationQuery identityPresentation,
            CatalogAccessPresentationQuery catalogPresentation,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            AccessCursorCodec cursors) {
        this.assignments = assignments;
        this.identityPresentation = identityPresentation;
        this.catalogPresentation = catalogPresentation;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping
    public ResponseEntity<AssignmentViewPage> list(
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
        require(actor, AdministrativePermissions.ACCESS_ASSIGNMENT_READ,
                AdministrativeResource.collection("access-assignment"), correlationId);

        AssignmentFilter filter = new AssignmentFilter(
                identityId,
                parseEnum(lifecycleState, AccessAssignment.LifecycleState.class, "lifecycleState", correlationId),
                parseEnum(targetKind, AccessAssignment.TargetKind.class, "targetKind", correlationId),
                targetId,
                parseEnum(provenance, AccessAssignment.ProvenanceKind.class, "provenance", correlationId),
                parseEnum(validity, AssignmentValidity.class, "validity", correlationId));
        int effectiveLimit = validateLimit(limit, correlationId);
        AssignmentPosition position = decode(cursor, actor, filter, correlationId);
        Instant evaluatedAt = Instant.now();
        var page = assignments.list(actor.tenant(), filter, position, effectiveLimit, evaluatedAt);
        ViewLabels labels = labels(actor, page.items());
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AssignmentViewPage(
                        page.items().stream().map(value -> view(value, evaluatedAt, labels)).toList(),
                        cursors.encodeAssignmentsForContext(actor.tenant(), filter.cursorBinding(), page.nextPosition()),
                        evaluatedAt));
    }

    @GetMapping("/{assignmentId}")
    public ResponseEntity<AssignmentView> get(
            @PathVariable UUID assignmentId,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.ACCESS_ASSIGNMENT_READ,
                new AdministrativeResource("access-assignment", assignmentId), correlationId);
        AccessAssignment value = assignments.find(actor.tenant(), assignmentId)
                .orElseThrow(() -> AccessApiException.notFound(correlationId));
        Instant evaluatedAt = Instant.now();
        ViewLabels labels = labels(actor, List.of(value));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"rev-" + value.revision() + "\"")
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(view(value, evaluatedAt, labels));
    }

    private AssignmentView view(
            AccessAssignment value,
            Instant evaluatedAt,
            ViewLabels labels) {
        UUID targetId = value.targetKind() == AccessAssignment.TargetKind.ROLE
                ? value.roleId() : value.entitlementId();
        String targetLabel = value.targetKind() == AccessAssignment.TargetKind.ROLE
                ? labels.catalog().roles().get(targetId)
                : labels.catalog().entitlements().get(targetId);

        return new AssignmentView(
                value.id(), value.identityId(), labels.identity().identities().get(value.identityId()),
                value.targetKind().name(), targetId, targetLabel,
                value.principalConstraintKind().name(), value.specificPrincipalId(),
                labels.identity().principals().get(value.specificPrincipalId()),
                value.provenanceKind().name(), value.provenanceRefId(),
                value.lifecycleState().name(), validity(value, evaluatedAt).name(), evaluatedAt,
                value.validFrom(), value.validUntil(), value.revision(), value.createdAt(), value.updatedAt());
    }

    private ViewLabels labels(
            AuthenticatedAdministrativeActor actor,
            List<AccessAssignment> values) {
        Set<UUID> identityIds = new LinkedHashSet<>();
        Set<UUID> principalIds = new LinkedHashSet<>();
        Set<UUID> roleIds = new LinkedHashSet<>();
        Set<UUID> entitlementIds = new LinkedHashSet<>();

        for (AccessAssignment value : values) {
            if (canRead(actor, AdministrativePermissions.IDENTITY_READ, "identity", value.identityId())) {
                identityIds.add(value.identityId());
            }
            if (value.specificPrincipalId() != null
                    && canRead(actor, AdministrativePermissions.PRINCIPAL_READ, "principal", value.specificPrincipalId())) {
                principalIds.add(value.specificPrincipalId());
            }
            UUID targetId = value.targetKind() == AccessAssignment.TargetKind.ROLE
                    ? value.roleId() : value.entitlementId();
            if (value.targetKind() == AccessAssignment.TargetKind.ROLE
                    && canRead(actor, AdministrativePermissions.ROLE_READ, "role", targetId)) {
                roleIds.add(targetId);
            } else if (value.targetKind() == AccessAssignment.TargetKind.ENTITLEMENT
                    && canRead(actor, AdministrativePermissions.ENTITLEMENT_READ, "entitlement", targetId)) {
                entitlementIds.add(targetId);
            }
        }

        return new ViewLabels(
                identityPresentation.labels(actor.tenant(), identityIds, principalIds),
                catalogPresentation.labels(actor.tenant(), roleIds, entitlementIds));
    }

    private boolean canRead(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            String resourceType,
            UUID resourceId) {
        return resourceId != null && authorization.authorize(
                actor, permission, new AdministrativeResource(resourceType, resourceId), Instant.now()).allowed();
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(actor, permission, resource, Instant.now()).allowed()) {
            throw AccessApiException.forbidden(correlationId);
        }
    }

    private AssignmentPosition decode(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            AssignmentFilter filter,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeAssignmentsForContext(cursor, actor.tenant(), filter.cursorBinding());
        } catch (IllegalArgumentException invalid) {
            throw AccessApiException.validation(
                    correlationId, "cursor", "invalid_cursor",
                    "cursor is invalid, expired, or does not belong to the current filters.");
        }
    }

    private static AssignmentValidity validity(AccessAssignment value, Instant at) {
        if (value.validFrom() != null && value.validFrom().isAfter(at)) return AssignmentValidity.FUTURE;
        if (value.validUntil() != null && !value.validUntil().isAfter(at)) return AssignmentValidity.ENDED;
        return AssignmentValidity.CURRENT;
    }

    private static <E extends Enum<E>> E parseEnum(
            String value,
            Class<E> type,
            String field,
            UUID correlationId) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException invalid) {
            throw AccessApiException.validation(
                    correlationId, field, "invalid_enum", field + " is not a supported value.");
        }
    }

    private static int validateLimit(Integer limit, UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > 200) {
            throw AccessApiException.validation(
                    correlationId, "limit", "out_of_range", "limit must be between 1 and 200.");
        }
        return value;
    }

    private record ViewLabels(
            IdentityAccessPresentationQuery.Labels identity,
            CatalogAccessPresentationQuery.Labels catalog) {}

    public record AssignmentView(
            UUID id,
            UUID identityId,
            String identityLabel,
            String targetKind,
            UUID targetId,
            String targetLabel,
            String principalConstraintKind,
            UUID specificPrincipalId,
            String specificPrincipalLabel,
            String provenanceKind,
            UUID provenanceRefId,
            String lifecycleState,
            String validityState,
            Instant evaluatedAt,
            Instant validFrom,
            Instant validUntil,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    public record AssignmentViewPage(
            List<AssignmentView> items,
            String nextCursor,
            Instant evaluatedAt) {}
}
