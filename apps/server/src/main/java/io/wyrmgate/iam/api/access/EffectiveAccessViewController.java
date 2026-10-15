package io.wyrmgate.iam.api.access;

import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveDetail;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectivePosition;
import io.wyrmgate.iam.access.application.EffectiveAccessReadService;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.identity.application.IdentityQueryService;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Access-owned read-only operator presentation for EffectiveAccess.
 *
 * <p>Projection semantics stay in EffectiveAccessReadService. Identity and Entitlement labels are
 * optional presentation data composed through their owning capability query services and only when
 * the caller has the corresponding read authority.</p>
 */
@RestController
@RequestMapping("/api/v1/effective-access-views")
public final class EffectiveAccessViewController {
    private static final int DEFAULT_LIMIT = 50;

    private final EffectiveAccessReadService effectiveAccess;
    private final IdentityQueryService identities;
    private final CatalogQueryService catalog;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final AccessCursorCodec cursors;

    public EffectiveAccessViewController(
            EffectiveAccessReadService effectiveAccess,
            IdentityQueryService identities,
            CatalogQueryService catalog,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            AccessCursorCodec cursors) {
        this.effectiveAccess = effectiveAccess;
        this.identities = identities;
        this.catalog = catalog;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping
    public ResponseEntity<EffectiveViewPage> list(
            @RequestParam(required = false) UUID identityId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.EFFECTIVE_ACCESS_READ,
                AdministrativeResource.collection("effective-access"), correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        EffectivePosition position = decode(cursor, actor, identityId, correlationId);
        Instant evaluatedAt = Instant.now();
        var page = effectiveAccess.list(
                actor.tenant(), identityId, position, effectiveLimit, evaluatedAt);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new EffectiveViewPage(
                        page.items().stream().map(value -> view(actor, value)).toList(),
                        cursors.encodeEffective(actor.tenant(), identityId, page.nextPosition())));
    }

    @GetMapping("/{effectiveAccessId}")
    public ResponseEntity<EffectiveViewResource> get(
            @PathVariable UUID effectiveAccessId,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.EFFECTIVE_ACCESS_READ,
                new AdministrativeResource("effective-access", effectiveAccessId), correlationId);
        EffectiveDetail detail = effectiveAccess.find(actor.tenant(), effectiveAccessId, Instant.now())
                .orElseThrow(() -> AccessApiException.notFound(correlationId));
        EffectiveViewSummary summary = view(actor, detail.effectiveAccess());
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new EffectiveViewResource(
                        summary.id(), summary.identityId(), summary.identityLabel(), summary.entitlementId(),
                        summary.entitlementLabel(), summary.principalConstraintKey(), summary.supportCount(),
                        summary.computedAt(), summary.projectionGeneration(),
                        detail.supports().stream().map(value -> new SupportView(
                                value.accessAssignmentId(), value.pathHash(), value.pathDepth(), value.roleVersionPath()))
                                .toList()));
    }

    private EffectiveViewSummary view(
            AuthenticatedAdministrativeActor actor,
            EffectiveAccess value) {
        String identityLabel = null;
        if (canRead(actor, AdministrativePermissions.IDENTITY_READ, "identity", value.identityId())) {
            identityLabel = identities.findIdentity(actor.tenant(), value.identityId())
                    .map(identity -> identity.displayName()).orElse(null);
        }
        String entitlementLabel = null;
        if (canRead(actor, AdministrativePermissions.ENTITLEMENT_READ, "entitlement", value.entitlementId())) {
            entitlementLabel = catalog.findEntitlement(actor.tenant(), value.entitlementId())
                    .map(entitlement -> entitlement.code()).orElse(null);
        }
        return new EffectiveViewSummary(
                value.id(), value.identityId(), identityLabel, value.entitlementId(), entitlementLabel,
                value.principalConstraintKey(), value.supportCount(), value.computedAt(), value.projectionGeneration());
    }

    private boolean canRead(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            String resourceType,
            UUID resourceId) {
        return authorization.authorize(
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

    private EffectivePosition decode(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            UUID identityId,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeEffective(cursor, actor.tenant(), identityId);
        } catch (IllegalArgumentException invalid) {
            throw AccessApiException.validation(
                    correlationId, "cursor", "invalid_cursor",
                    "cursor is invalid, expired, or does not belong to the current Identity filter.");
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

    public record EffectiveViewSummary(
            UUID id,
            UUID identityId,
            String identityLabel,
            UUID entitlementId,
            String entitlementLabel,
            String principalConstraintKey,
            int supportCount,
            Instant computedAt,
            long projectionGeneration) {}

    public record SupportView(
            UUID accessAssignmentId,
            String pathHash,
            int pathDepth,
            List<UUID> roleVersionPath) {}

    public record EffectiveViewResource(
            UUID id,
            UUID identityId,
            String identityLabel,
            UUID entitlementId,
            String entitlementLabel,
            String principalConstraintKey,
            int supportCount,
            Instant computedAt,
            long projectionGeneration,
            List<SupportView> supports) {}

    public record EffectiveViewPage(
            List<EffectiveViewSummary> items,
            String nextCursor) {}
}
