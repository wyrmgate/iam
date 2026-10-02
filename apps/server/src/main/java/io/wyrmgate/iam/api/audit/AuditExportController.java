package io.wyrmgate.iam.api.audit;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.audit.AuditApiModels.AuditExportCreateRequest;
import io.wyrmgate.iam.api.audit.AuditApiModels.AuditExportResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.audit.application.AuditExportService;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditFilter;
import io.wyrmgate.iam.audit.domain.AuditExportOperation;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.IdempotencyConflictException;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/audit-exports")
final class AuditExportController {

    private final AuditExportService exports;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;

    AuditExportController(
            AuditExportService exports,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids) {
        this.exports = exports;
        this.authorization = authorization;
        this.ids = ids;
    }

    @PostMapping
    ResponseEntity<AuditExportResource> create(
            @RequestBody AuditExportCreateRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId = AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireExport(actor, AdministrativeResource.collection("audit"), correlationId);
        requireEnabled(correlationId);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw AuditApiException.validation(
                    correlationId,
                    "Idempotency-Key",
                    "required",
                    "Idempotency-Key is required.");
        }
        if (body == null || body.occurredFrom() == null || body.occurredUntil() == null) {
            throw AuditApiException.validation(
                    correlationId,
                    "occurredFrom",
                    "required",
                    "occurredFrom and occurredUntil are required.");
        }
        if (!body.occurredUntil().isAfter(body.occurredFrom())) {
            throw AuditApiException.validation(
                    correlationId,
                    "occurredUntil",
                    "invalid_window",
                    "occurredUntil must be after occurredFrom.");
        }

        AuditFilter filter = filter(body, correlationId);
        RequestFingerprint fingerprint = fingerprint(filter, body.occurredFrom(), body.occurredUntil());

        AuditExportOperation operation;
        try {
            operation = exports.request(
                    actor.tenant(),
                    actor.identityId(),
                    filter,
                    body.occurredFrom(),
                    body.occurredUntil(),
                    idempotencyKey.trim(),
                    fingerprint);
        } catch (IdempotencyConflictException conflict) {
            throw AuditApiException.conflict(
                    correlationId,
                    "idempotency_conflict",
                    "The Idempotency-Key was already used with a different export request.");
        } catch (IllegalStateException unavailable) {
            if ("audit_export_idempotency_in_progress".equals(unavailable.getMessage())) {
                throw AuditApiException.conflict(
                        correlationId,
                        "idempotency_in_progress",
                        "The same export request is already being processed.");
            }
            throw unavailable;
        }

        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, "/api/v1/audit-exports/" + operation.id())
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource(operation));
    }

    @GetMapping("/{auditExportId}")
    ResponseEntity<AuditExportResource> get(
            @PathVariable UUID auditExportId,
            HttpServletRequest request) {
        UUID correlationId = AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireExport(
                actor,
                new AdministrativeResource("audit", auditExportId),
                correlationId);
        AuditExportOperation operation = find(actor, auditExportId, correlationId);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .eTag("\"rev-" + operation.revision() + "\"")
                .body(resource(operation));
    }

    @PostMapping("/{auditExportId}:download")
    ResponseEntity<StreamingResponseBody> download(
            @PathVariable UUID auditExportId,
            HttpServletRequest request) {
        UUID correlationId = AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireExport(
                actor,
                new AdministrativeResource("audit-export", auditExportId),
                correlationId);

        AuditExportService.Download download;
        try {
            download = exports.openDownload(actor.tenant(), auditExportId);
        } catch (IllegalArgumentException missing) {
            throw AuditApiException.notFound(correlationId);
        } catch (IllegalStateException state) {
            if ("audit_export_not_ready".equals(state.getMessage())) {
                throw AuditApiException.conflict(
                        correlationId,
                        "audit_export_not_ready",
                        "The Audit export artifact is not ready.");
            }
            if ("audit_export_artifact_expired".equals(state.getMessage())) {
                throw AuditApiException.gone(
                        correlationId,
                        "audit_export_artifact_expired",
                        "The Audit export artifact has expired.");
            }
            throw state;
        }

        StreamingResponseBody body = output -> {
            try (var input = download.stream()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.filename() + "\"")
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.operation().byteCount())
                .body(body);
    }

    private AuditExportOperation find(
            AuthenticatedAdministrativeActor actor,
            UUID exportId,
            UUID correlationId) {
        try {
            return exports.find(actor.tenant(), exportId);
        } catch (IllegalArgumentException missing) {
            throw AuditApiException.notFound(correlationId);
        }
    }

    private void requireEnabled(UUID correlationId) {
        if (!exports.enabled()) {
            throw AuditApiException.unavailable(
                    correlationId,
                    "audit_export_unavailable",
                    "Audit export is not enabled for this deployment.");
        }
    }

    private void requireExport(
            AuthenticatedAdministrativeActor actor,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(
                actor,
                AdministrativePermissions.AUDIT_EXPORT,
                resource,
                Instant.now()).allowed()) {
            throw AuditApiException.forbidden(correlationId);
        }
    }

    private static AuditFilter filter(
            AuditExportCreateRequest body,
            UUID correlationId) {
        AuditOutcome outcome = null;
        if (body.outcome() != null) {
            try {
                outcome = AuditOutcome.valueOf(body.outcome().trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException invalid) {
                throw AuditApiException.validation(
                        correlationId,
                        "outcome",
                        "invalid_outcome",
                        "outcome must be SUCCESS, DENIED, or FAILURE.");
            }
        }
        try {
            return new AuditFilter(
                    body.actorId(),
                    body.actionType(),
                    body.resourceType(),
                    body.resourceId(),
                    outcome,
                    body.correlationId());
        } catch (IllegalArgumentException invalid) {
            throw AuditApiException.validation(
                    correlationId,
                    "filter",
                    "invalid_filter",
                    "Audit export filter is invalid.");
        }
    }

    private static RequestFingerprint fingerprint(
            AuditFilter filter,
            Instant occurredFrom,
            Instant occurredUntil) {
        String canonical = String.join(
                "|",
                value(filter.actorId()),
                value(filter.actionType()),
                value(filter.resourceType()),
                value(filter.resourceId()),
                value(filter.outcome()),
                value(filter.correlationId()),
                occurredFrom.toString(),
                occurredUntil.toString(),
                AuditExportOperation.NDJSON_V1);
        return RequestFingerprint.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static AuditExportResource resource(AuditExportOperation value) {
        return new AuditExportResource(
                value.id(),
                value.requestedByIdentityId(),
                value.filter().actorId(),
                value.filter().actionType(),
                value.filter().resourceType(),
                value.filter().resourceId(),
                value.filter().outcome() == null ? null : value.filter().outcome().name(),
                value.filter().correlationId(),
                value.occurredFrom(),
                value.occurredUntil(),
                value.snapshotRecordedAt(),
                value.schemaVersion(),
                value.state().name(),
                value.recordCount(),
                value.byteCount(),
                value.sha256Hex(),
                value.artifactExpiresAt(),
                value.failureCode(),
                value.revision(),
                value.completedAt(),
                value.createdAt(),
                value.updatedAt());
    }
}
