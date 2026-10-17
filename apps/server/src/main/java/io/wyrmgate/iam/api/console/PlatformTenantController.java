package io.wyrmgate.iam.api.console;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.application.PlatformOwnerAuthorityQuery;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.platform.tenant.PlatformTenantOverviewQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only deployment-scoped Tenant inventory for current viable PLATFORM_OWNER actors. */
@RestController
@RequestMapping("/api/v1/console/platform")
public final class PlatformTenantController {
    private static final int MAX_LIMIT = 100;

    private final PlatformOwnerAuthorityQuery platformOwners;
    private final PlatformTenantOverviewQuery tenants;
    private final PlatformTenantCursorCodec cursors;

    public PlatformTenantController(
            PlatformOwnerAuthorityQuery platformOwners,
            PlatformTenantOverviewQuery tenants,
            PlatformTenantCursorCodec cursors) {
        this.platformOwners = platformOwners;
        this.tenants = tenants;
        this.cursors = cursors;
    }

    @GetMapping("/tenants")
    ResponseEntity<TenantPageResource> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        requirePlatformOwner(request);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100.");
        }

        PlatformTenantCursorCodec.Position position;
        try {
            position = cursor == null || cursor.isBlank() ? null : cursors.decode(cursor);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid continuation cursor.");
        }

        List<PlatformTenantOverviewQuery.TenantSummary> rows = tenants.listAfter(
                position == null ? null : position.createdAt(),
                position == null ? null : position.id(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<PlatformTenantOverviewQuery.TenantSummary> visible = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasMore && !visible.isEmpty()) {
            PlatformTenantOverviewQuery.TenantSummary last = visible.get(visible.size() - 1);
            nextCursor = cursors.encode(new PlatformTenantCursorCodec.Position(last.createdAt(), last.id()));
        }

        List<TenantResource> items = visible.stream()
                .map(PlatformTenantController::resource)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TenantPageResource(items, nextCursor));
    }

    @GetMapping("/tenants/{tenantId}")
    ResponseEntity<TenantResource> detail(
            HttpServletRequest request,
            @PathVariable UUID tenantId) {
        requirePlatformOwner(request);
        PlatformTenantOverviewQuery.TenantSummary tenant = tenants.find(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant was not found."));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(resource(tenant));
    }

    private void requirePlatformOwner(HttpServletRequest request) {
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        if (!platformOwners.isViablePlatformOwner(actor.tenant(), actor.identityId(), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform Owner authority is required.");
        }
    }

    private static TenantResource resource(PlatformTenantOverviewQuery.TenantSummary tenant) {
        return new TenantResource(
                tenant.id(),
                tenant.displayName(),
                tenant.revision(),
                tenant.createdAt(),
                tenant.updatedAt());
    }

    public record TenantResource(UUID id, String displayName, long revision, Instant createdAt, Instant updatedAt) {}
    public record TenantPageResource(List<TenantResource> items, String nextCursor) {
        public TenantPageResource {
            items = List.copyOf(items);
        }
    }
}
