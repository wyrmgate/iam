package io.wyrmgate.iam.api.access;

import io.wyrmgate.iam.access.application.AccessAssignmentQueryService;
import io.wyrmgate.iam.access.application.AccessQueryModels.AssignmentPosition;
import io.wyrmgate.iam.access.domain.AccessAssignment;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.access.AccessApiModels.AccessAssignmentPage;
import io.wyrmgate.iam.api.access.AccessApiModels.AccessAssignmentResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Access-owned bounded read adapter for the Identity 360 workbench. */
@RestController
@RequestMapping("/api/v1/identities/{identityId}/access-assignments")
public final class IdentityAccessAssignmentController {
    private static final int DEFAULT_LIMIT = 50;

    private final AccessAssignmentQueryService assignments;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final AccessCursorCodec cursors;

    public IdentityAccessAssignmentController(
            AccessAssignmentQueryService assignments,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            AccessCursorCodec cursors) {
        this.assignments = assignments;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping
    public ResponseEntity<AccessAssignmentPage> list(
            @PathVariable UUID identityId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        if (!authorization.authorize(
                actor,
                AdministrativePermissions.ACCESS_ASSIGNMENT_READ,
                AdministrativeResource.collection("access-assignment"),
                Instant.now()).allowed()) {
            throw AccessApiException.forbidden(correlationId);
        }
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > 200) {
            throw AccessApiException.validation(
                    correlationId, "limit", "out_of_range", "limit must be between 1 and 200.");
        }
        AssignmentPosition position = null;
        if (cursor != null) {
            try {
                position = cursors.decodeAssignments(cursor, actor.tenant(), identityId);
            } catch (IllegalArgumentException invalid) {
                throw AccessApiException.validation(
                        correlationId, "cursor", "invalid_cursor", "cursor is invalid or malformed.");
            }
        }
        var page = assignments.list(actor.tenant(), identityId, position, effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AccessAssignmentPage(
                        page.items().stream().map(IdentityAccessAssignmentController::resource).toList(),
                        cursors.encodeAssignments(actor.tenant(), identityId, page.nextPosition())));
    }

    private static AccessAssignmentResource resource(AccessAssignment value) {
        return new AccessAssignmentResource(
                value.id(), value.identityId(), value.targetKind().name(), value.roleId(), value.entitlementId(),
                value.principalConstraintKind().name(), value.specificPrincipalId(), value.provenanceKind().name(),
                value.provenanceRefId(), value.lifecycleState().name(), value.validFrom(), value.validUntil(),
                value.revision(), value.createdAt(), value.updatedAt());
    }
}
