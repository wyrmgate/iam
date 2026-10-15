package io.wyrmgate.iam.api.catalog;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetSearchQuery;
import io.wyrmgate.iam.catalog.application.CatalogAccessTargetSearchQuery.Kind;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bounded Catalog-owned search for Access Role/Entitlement selection. */
@RestController
@RequestMapping("/api/v1/catalog/access-targets")
public final class CatalogAccessTargetSearchController {
    private final CatalogAccessTargetSearchQuery search;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;

    public CatalogAccessTargetSearchController(
            CatalogAccessTargetSearchQuery search,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids) {
        this.search = search;
        this.authorization = authorization;
        this.ids = ids;
    }

    @GetMapping
    public ResponseEntity<SearchPage> search(
            @RequestParam String kind,
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "20") Integer limit,
            HttpServletRequest request) {
        UUID correlationId = CatalogApiRequestContext.resolveCorrelationId(request, ids);
        Kind parsedKind;
        try {
            parsedKind = Kind.valueOf(kind);
        } catch (IllegalArgumentException invalid) {
            throw CatalogApiException.validation(
                    correlationId, "kind", "invalid_enum", "kind must be ROLE or ENTITLEMENT.");
        }
        if (q == null || q.indexOf('\u0000') >= 0 || q.trim().length() > 200) {
            throw CatalogApiException.validation(
                    correlationId, "q", "invalid_value", "q must contain no NUL character and be at most 200 characters.");
        }
        int effectiveLimit = limit == null ? 20 : limit;
        if (effectiveLimit < 1 || effectiveLimit > 20) {
            throw CatalogApiException.validation(
                    correlationId, "limit", "out_of_range", "limit must be between 1 and 20.");
        }

        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        AdministrativePermission permission = parsedKind == Kind.ROLE
                ? AdministrativePermissions.ROLE_READ
                : AdministrativePermissions.ENTITLEMENT_READ;
        String resourceType = parsedKind == Kind.ROLE ? "role" : "entitlement";
        if (!authorization.authorize(
                actor, permission, AdministrativeResource.collection(resourceType), Instant.now()).allowed()) {
            throw CatalogApiException.forbidden(correlationId);
        }

        List<SearchResult> items = search.search(actor.tenant(), parsedKind, q, effectiveLimit).stream()
                .map(value -> new SearchResult(
                        value.id(), value.kind().name(), value.label(), value.code(), value.secondaryLabel()))
                .toList();
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SearchPage(items));
    }

    public record SearchResult(
            UUID id,
            String kind,
            String label,
            String code,
            String secondaryLabel) {}

    public record SearchPage(List<SearchResult> items) {}
}
