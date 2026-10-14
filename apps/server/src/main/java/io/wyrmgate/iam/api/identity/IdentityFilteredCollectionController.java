package io.wyrmgate.iam.api.identity;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.identity.IdentityApiModels.IdentityPage;
import io.wyrmgate.iam.api.identity.IdentityApiModels.IdentityResource;
import io.wyrmgate.iam.api.identity.IdentityApiModels.ProfileResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityListFilter;
import io.wyrmgate.iam.identity.application.IdentityQueryModels.IdentityPagePosition;
import io.wyrmgate.iam.identity.application.IdentityQueryService;
import io.wyrmgate.iam.identity.domain.Identity;
import io.wyrmgate.iam.identity.domain.IdentityLifecycleState;
import io.wyrmgate.iam.identity.domain.IdentityType;
import io.wyrmgate.iam.platform.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Filtered variants of the authoritative Identity collection read.
 *
 * <p>The parameter conditions make these mappings strictly more specific than the
 * unfiltered collection mapping in {@link IdentityController}. They remain the same
 * semantic collection resource and use the same authorization, page bounds and
 * authoritative Identity query service.</p>
 */
@RestController
@RequestMapping("/api/v1/identities")
final class IdentityFilteredCollectionController {

    private static final int DEFAULT_LIMIT = 50;

    private final IdentityQueryService queries;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final IdentityCursorCodec cursors;

    IdentityFilteredCollectionController(
            IdentityQueryService queries,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            IdentityCursorCodec cursors) {
        this.queries = queries;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping(params = "q")
    ResponseEntity<IdentityPage> listWithName(
            @RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return list(q, type, lifecycle, cursor, limit, request);
    }

    @GetMapping(params = {"!q", "type"})
    ResponseEntity<IdentityPage> listWithType(
            @RequestParam String type,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return list(null, type, lifecycle, cursor, limit, request);
    }

    @GetMapping(params = {"!q", "!type", "lifecycle"})
    ResponseEntity<IdentityPage> listWithLifecycle(
            @RequestParam String lifecycle,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return list(null, null, lifecycle, cursor, limit, request);
    }

    private ResponseEntity<IdentityPage> list(
            String q,
            String type,
            String lifecycle,
            String cursor,
            Integer limit,
            HttpServletRequest request) {
        UUID correlationId = IdentityApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        Instant now = Instant.now();
        if (!authorization.authorize(
                actor,
                AdministrativePermissions.IDENTITY_READ,
                AdministrativeResource.collection("identity"),
                now).allowed()) {
            throw IdentityApiException.forbidden(correlationId);
        }

        IdentityListFilter filter = parseFilter(q, type, lifecycle, correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        IdentityPagePosition position = decodeCursor(cursor, actor, filter, correlationId);
        var page = queries.listIdentities(actor.tenant(), filter, position, effectiveLimit);

        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .body(new IdentityPage(
                        page.items().stream().map(IdentityFilteredCollectionController::resource).toList(),
                        cursors.encodeFilteredIdentity(
                                actor.tenant(),
                                filter.cursorBinding(),
                                page.nextPosition())));
    }

    private IdentityPagePosition decodeCursor(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            IdentityListFilter filter,
            UUID correlationId) {
        if (cursor == null) return null;
        try {
            return cursors.decodeFilteredIdentity(cursor, actor.tenant(), filter.cursorBinding());
        } catch (IllegalArgumentException invalid) {
            throw IdentityApiException.validation(
                    correlationId,
                    "cursor",
                    "invalid_cursor",
                    "cursor is invalid, expired, or does not belong to the current filters.");
        }
    }

    private static IdentityListFilter parseFilter(
            String q,
            String type,
            String lifecycle,
            UUID correlationId) {
        String prefix = q == null ? "" : q.trim();
        if (prefix.length() > 300 || prefix.indexOf('\u0000') >= 0) {
            throw IdentityApiException.validation(
                    correlationId,
                    "q",
                    "invalid_value",
                    "q must contain no NUL character and be at most 300 characters.");
        }
        IdentityType parsedType = parseEnum(type, IdentityType.class, "type", correlationId);
        IdentityLifecycleState parsedLifecycle = parseEnum(
                lifecycle,
                IdentityLifecycleState.class,
                "lifecycle",
                correlationId);
        return new IdentityListFilter(prefix, parsedType, parsedLifecycle);
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
            throw IdentityApiException.validation(
                    correlationId,
                    field,
                    "invalid_enum",
                    field + " is not a supported value.");
        }
    }

    private static int validateLimit(Integer limit, UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > 200) {
            throw IdentityApiException.validation(
                    correlationId,
                    "limit",
                    "out_of_range",
                    "limit must be between 1 and 200.");
        }
        return value;
    }

    private static IdentityResource resource(Identity identity) {
        return new IdentityResource(
                identity.id(),
                identity.type().name(),
                new ProfileResource(identity.profile().identityType().name()),
                identity.lifecycleState().name(),
                identity.displayName(),
                identity.revision(),
                identity.createdAt(),
                identity.updatedAt());
    }
}
