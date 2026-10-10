package io.wyrmgate.iam.api.administration;

import io.wyrmgate.iam.administration.application.CurrentAdministrativeAuthorityProjection;
import io.wyrmgate.iam.administration.application.EffectiveAdministrativeAuthority;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.CurrentAdministrativeAuthorityResource;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.EffectiveAuthorityResource;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.PermissionResource;
import io.wyrmgate.iam.api.administration.AdministrationApiModels.ScopeResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated self-read projection for console navigation/action optimization. */
@RestController
@RequestMapping("/api/v1")
public final class CurrentAdministrativeAuthorityController {

    private final CurrentAdministrativeAuthorityProjection projection;
    private final IdGenerator ids;

    public CurrentAdministrativeAuthorityController(
            CurrentAdministrativeAuthorityProjection projection,
            IdGenerator ids) {
        this.projection = projection;
        this.ids = ids;
    }

    @GetMapping("/current-administrative-authority")
    public ResponseEntity<CurrentAdministrativeAuthorityResource> current(HttpServletRequest request) {
        UUID correlationId = AdministrationApiRequestContext.resolveCorrelationId(request, ids);
        var actor = ControlPlaneActorRequestContext.require(request);
        Instant evaluatedAt = Instant.now();
        var result = projection.current(actor, evaluatedAt);
        var body = new CurrentAdministrativeAuthorityResource(
                actor.tenant().tenantId(),
                actor.identityId(),
                result.administrativelyEligible(),
                evaluatedAt,
                result.authorities().stream()
                        .map(CurrentAdministrativeAuthorityController::resource)
                        .toList());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Correlation-Id", correlationId.toString())
                .body(body);
    }

    private static EffectiveAuthorityResource resource(EffectiveAdministrativeAuthority authority) {
        var permission = authority.permission();
        var scope = authority.scope();
        return new EffectiveAuthorityResource(
                new PermissionResource(permission.resourceType(), permission.action(), permission.key()),
                new ScopeResource(
                        scope.type().name(), scope.resourceType(), scope.resourceId(), scope.scopeKey()),
                authority.source().name(),
                authority.sourceId(),
                authority.validFrom(),
                authority.validUntil());
    }
}
