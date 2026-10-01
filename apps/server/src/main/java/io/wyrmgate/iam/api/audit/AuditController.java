package io.wyrmgate.iam.api.audit;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.audit.AuditApiModels.AuditRecordPage;
import io.wyrmgate.iam.api.audit.AuditApiModels.AuditRecordResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditFilter;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditPagePosition;
import io.wyrmgate.iam.audit.application.AuditQueryService;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditRecord;
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

@RestController
@RequestMapping("/api/v1/audit-records")
final class AuditController {

    private static final int DEFAULT_LIMIT = 50;

    private final AuditQueryService queries;
    private final AdministrativeAuthorizationService authorization;
    private final AuditCursorCodec cursors;
    private final IdGenerator ids;

    AuditController(
            AuditQueryService queries,
            AdministrativeAuthorizationService authorization,
            AuditCursorCodec cursors,
            IdGenerator ids) {
        this.queries = queries;
        this.authorization = authorization;
        this.cursors = cursors;
        this.ids = ids;
    }

    @GetMapping
    ResponseEntity<AuditRecordPage> list(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) UUID correlationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID requestCorrelationId =
                AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                AdministrativeResource.collection("audit-record"),
                requestCorrelationId);

        AuditFilter filter = filter(
                actorId,
                actionType,
                resourceType,
                resourceId,
                outcome,
                correlationId,
                requestCorrelationId);
        int effectiveLimit = validateLimit(limit, requestCorrelationId);
        AuditPagePosition position = decode(
                cursor,
                actor,
                filter,
                requestCorrelationId);
        var page = queries.list(
                actor.tenant(),
                filter,
                position,
                effectiveLimit);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        "X-Correlation-Id",
                        requestCorrelationId.toString())
                .body(new AuditRecordPage(
                        page.items().stream()
                                .map(AuditController::resource)
                                .toList(),
                        cursors.encode(
                                actor.tenant(),
                                filter,
                                page.nextPosition())));
    }

    @GetMapping("/{auditRecordId}")
    ResponseEntity<AuditRecordResource> get(
            @PathVariable UUID auditRecordId,
            HttpServletRequest request) {
        UUID correlationId =
                AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor =
                ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                new AdministrativeResource(
                        "audit-record", auditRecordId),
                correlationId);
        AuditRecord record = queries.findById(
                        actor.tenant(), auditRecordId)
                .orElseThrow(() ->
                        AuditApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        "X-Correlation-Id",
                        correlationId.toString())
                .body(resource(record));
    }

    private void requireRead(
            AuthenticatedAdministrativeActor actor,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(
                actor,
                AdministrativePermissions.AUDIT_READ,
                resource,
                Instant.now()).allowed()) {
            throw AuditApiException.forbidden(correlationId);
        }
    }

    private static AuditFilter filter(
            UUID actorId,
            String actionType,
            String resourceType,
            UUID resourceId,
            String outcome,
            UUID correlationId,
            UUID requestCorrelationId) {
        AuditOutcome parsedOutcome = null;
        if (outcome != null) {
            try {
                parsedOutcome = AuditOutcome.valueOf(
                        outcome.trim().toUpperCase(
                                java.util.Locale.ROOT));
            } catch (RuntimeException invalid) {
                throw AuditApiException.validation(
                        requestCorrelationId,
                        "outcome",
                        "invalid_outcome",
                        "outcome must be SUCCESS, DENIED, or FAILURE.");
            }
        }
        try {
            return new AuditFilter(
                    actorId,
                    actionType,
                    resourceType,
                    resourceId,
                    parsedOutcome,
                    correlationId);
        } catch (IllegalArgumentException invalid) {
            throw AuditApiException.validation(
                    requestCorrelationId,
                    "filter",
                    "invalid_filter",
                    "Audit search filter is invalid.");
        }
    }

    private AuditPagePosition decode(
            String cursor,
            AuthenticatedAdministrativeActor actor,
            AuditFilter filter,
            UUID correlationId) {
        if (cursor == null) {
            return null;
        }
        try {
            return cursors.decode(cursor, actor.tenant(), filter);
        } catch (IllegalArgumentException invalid) {
            throw AuditApiException.validation(
                    correlationId,
                    "cursor",
                    "invalid_cursor",
                    "cursor is invalid or does not match this Audit search.");
        }
    }

    private static int validateLimit(
            Integer limit,
            UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > AuditQueryService.MAX_PAGE_SIZE) {
            throw AuditApiException.validation(
                    correlationId,
                    "limit",
                    "out_of_range",
                    "limit must be between 1 and 200.");
        }
        return value;
    }

    private static AuditRecordResource resource(AuditRecord value) {
        return new AuditRecordResource(
                value.id(),
                value.occurredAt(),
                value.recordedAt(),
                value.actorId(),
                value.actionType(),
                value.resourceType(),
                value.resourceId(),
                value.outcome().name(),
                value.correlationId(),
                value.causationId());
    }
}
