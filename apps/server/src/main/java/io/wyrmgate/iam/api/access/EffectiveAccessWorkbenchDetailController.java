package io.wyrmgate.iam.api.access;

import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupport;
import io.wyrmgate.iam.access.application.AccessQueryModels.EffectiveSupportPosition;
import io.wyrmgate.iam.access.application.EffectiveAccessRepository;
import io.wyrmgate.iam.access.application.EffectiveAccessSupportQuery;
import io.wyrmgate.iam.access.domain.EffectiveAccess;
import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
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

@RestController
@RequestMapping("/api/v1/console/access/effective")
public final class EffectiveAccessWorkbenchDetailController {
    private static final int DEFAULT_SUPPORT_LIMIT = 25;
    private static final int MAX_SUPPORT_LIMIT = 100;

    private final EffectiveAccessRepository effectiveAccess;
    private final EffectiveAccessSupportQuery supports;
    private final IdentityAccessReferenceQuery identityStatus;
    private final IdentityQueryService identities;
    private final CatalogQueryService catalog;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final AccessCursorCodec cursors;

    public EffectiveAccessWorkbenchDetailController(
            EffectiveAccessRepository effectiveAccess,
            EffectiveAccessSupportQuery supports,
            IdentityAccessReferenceQuery identityStatus,
            IdentityQueryService identities,
            CatalogQueryService catalog,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            AccessCursorCodec cursors) {
        this.effectiveAccess = effectiveAccess;
        this.supports = supports;
        this.identityStatus = identityStatus;
        this.identities = identities;
        this.catalog = catalog;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping("/{effectiveAccessId}/summary")
    public ResponseEntity<SummaryResource> summary(
            @PathVariable UUID effectiveAccessId,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        requireRead(actor, effectiveAccessId, now, correlationId);
        EffectiveAccess value = current(actor, effectiveAccessId, now, correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(actor, value));
    }

    @GetMapping("/{effectiveAccessId}/supports")
    public ResponseEntity<SupportPageResource> supportPage(
            @PathVariable UUID effectiveAccessId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = AccessApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        requireRead(actor, effectiveAccessId, now, correlationId);
        current(actor, effectiveAccessId, now, correlationId);
        int bounded = limit == null ? DEFAULT_SUPPORT_LIMIT : limit;
        if (bounded < 1 || bounded > MAX_SUPPORT_LIMIT) {
            throw AccessApiException.validation(correlationId, "limit", "out_of_range", "support limit must be between 1 and 100.");
        }
        EffectiveSupportPosition position = null;
        if (cursor != null) {
            try {
                position = cursors.decodeEffectiveSupports(cursor, actor.tenant(), effectiveAccessId);
            } catch (IllegalArgumentException invalid) {
                throw AccessApiException.validation(correlationId, "cursor", "invalid_cursor", "support cursor is invalid or does not match this EffectiveAccess.");
            }
        }
        List<EffectiveSupport> fetched = supports.page(actor.tenant(), effectiveAccessId, position, bounded + 1, now);
        boolean more = fetched.size() > bounded;
        List<EffectiveSupport> items = List.copyOf(fetched.subList(0, Math.min(bounded, fetched.size())));
        EffectiveSupportPosition next = more
                ? new EffectiveSupportPosition(items.getLast().accessAssignmentId(), items.getLast().pathHash())
                : null;
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(new SupportPageResource(
                        items.stream().map(support -> new SupportResource(
                                support.accessAssignmentId(), support.pathHash(), support.pathDepth(), support.roleVersionPath())).toList(),
                        cursors.encodeEffectiveSupports(actor.tenant(), effectiveAccessId, next)));
    }

    private EffectiveAccess current(
            AuthenticatedAdministrativeActor actor,
            UUID effectiveAccessId,
            Instant now,
            UUID correlationId) {
        EffectiveAccess value = effectiveAccess.findCurrentById(actor.tenant(), effectiveAccessId, now)
                .orElseThrow(() -> AccessApiException.notFound(correlationId));
        if (identityStatus.accessStatus(actor.tenant(), value.identityId()).status()
                != IdentityAccessReferenceQuery.AccessStatus.ACCESS_ELIGIBLE) {
            throw AccessApiException.notFound(correlationId);
        }
        List<EffectiveSupport> one = supports.page(actor.tenant(), effectiveAccessId, null, 1, now);
        if (one.isEmpty()) throw AccessApiException.notFound(correlationId);
        return value;
    }

    private SummaryResource resource(AuthenticatedAdministrativeActor actor, EffectiveAccess value) {
        String identityLabel = null;
        if (authorization.authorize(actor, AdministrativePermissions.IDENTITY_READ,
                new AdministrativeResource("identity", value.identityId()), Instant.now()).allowed()) {
            identityLabel = identities.findIdentity(actor.tenant(), value.identityId()).map(item -> item.displayName()).orElse(null);
        }
        String entitlementLabel = null;
        if (authorization.authorize(actor, AdministrativePermissions.ENTITLEMENT_READ,
                new AdministrativeResource("entitlement", value.entitlementId()), Instant.now()).allowed()) {
            entitlementLabel = catalog.findEntitlement(actor.tenant(), value.entitlementId()).map(item -> item.code()).orElse(null);
        }
        return new SummaryResource(
                value.id(), value.identityId(), identityLabel, value.entitlementId(), entitlementLabel,
                value.principalConstraintKey(), value.supportCount(), value.computedAt(), value.projectionGeneration());
    }

    private void requireRead(
            AuthenticatedAdministrativeActor actor,
            UUID effectiveAccessId,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(actor, AdministrativePermissions.EFFECTIVE_ACCESS_READ,
                new AdministrativeResource("effective-access", effectiveAccessId), now).allowed()) {
            throw AccessApiException.forbidden(correlationId);
        }
    }

    public record SummaryResource(
            UUID id,
            UUID identityId,
            String identityLabel,
            UUID entitlementId,
            String entitlementLabel,
            String principalConstraintKey,
            int supportCount,
            Instant computedAt,
            long projectionGeneration) {}

    public record SupportPageResource(List<SupportResource> items, String nextCursor) {}
    public record SupportResource(UUID accessAssignmentId, String pathHash, int pathDepth, List<UUID> roleVersionPath) {}
}
