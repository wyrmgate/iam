package io.wyrmgate.iam.api.console;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.platform.tenant.PlatformTenantOverviewQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Read-only Console composition endpoint for the server-resolved current Tenant. */
@RestController
@RequestMapping("/api/v1/console")
public final class TenantContextController {
    private final PlatformTenantOverviewQuery tenants;

    public TenantContextController(PlatformTenantOverviewQuery tenants) {
        this.tenants = tenants;
    }

    @GetMapping("/tenant-context")
    ResponseEntity<TenantContextResource> current(HttpServletRequest request) {
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        PlatformTenantOverviewQuery.TenantSummary tenant = tenants.find(actor.tenant().tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Current Tenant was not found."));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TenantContextResource(
                        tenant.id(), tenant.displayName(), tenant.revision(), tenant.createdAt(), tenant.updatedAt()));
    }

    public record TenantContextResource(UUID id, String displayName, long revision, Instant createdAt, Instant updatedAt) {}
}
