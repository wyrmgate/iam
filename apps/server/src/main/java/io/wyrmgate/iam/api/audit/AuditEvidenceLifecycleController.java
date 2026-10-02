package io.wyrmgate.iam.api.audit;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.audit.AuditApiModels.AuditLegalHoldCreateRequest;
import io.wyrmgate.iam.api.audit.AuditApiModels.AuditLegalHoldResource;
import io.wyrmgate.iam.api.audit.AuditApiModels.AuditPurgeCreateRequest;
import io.wyrmgate.iam.api.audit.AuditApiModels.AuditPurgeResource;
import io.wyrmgate.iam.api.audit.AuditApiModels.AuditSelectionRequest;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.audit.application.AuditEvidenceLifecycleService;
import io.wyrmgate.iam.audit.domain.AuditLegalHold;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.audit.domain.AuditPurgeOperation;
import io.wyrmgate.iam.audit.domain.AuditSelection;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.IdempotencyConflictException;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-evidence-lifecycle")
final class AuditEvidenceLifecycleController {

    private final AuditEvidenceLifecycleService lifecycle;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;

    AuditEvidenceLifecycleController(
            AuditEvidenceLifecycleService lifecycle,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids) {
        this.lifecycle = lifecycle;
        this.authorization = authorization;
        this.ids = ids;
    }

    @PostMapping("/legal-holds")
    ResponseEntity<AuditLegalHoldResource> createHold(
            @RequestBody AuditLegalHoldCreateRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId = AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.AUDIT_HOLD, AdministrativeResource.collection("audit"), correlationId);
        requireIdempotency(idempotencyKey, correlationId);
        if (body == null || body.selection() == null) {
            throw AuditApiException.validation(correlationId, "selection", "required", "selection is required.");
        }
        AuditSelection selection = selection(body.selection(), correlationId);
        try {
            AuditLegalHold hold = lifecycle.createHold(
                    actor.tenant(), selection,
                    required(body.reasonCode(), "reasonCode", correlationId),
                    required(body.caseReference(), "caseReference", correlationId),
                    actor.identityId(), correlationId, null,
                    idempotencyKey.trim(),
                    fingerprint("hold", selection, body.reasonCode(), body.caseReference(), null));
            return ResponseEntity.status(201)
                    .header(HttpHeaders.LOCATION, "/api/v1/audit-evidence-lifecycle/legal-holds/" + hold.id())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Correlation-Id", correlationId.toString())
                    .eTag(etag(hold.revision()))
                    .body(hold(hold));
        } catch (IdempotencyConflictException conflict) {
            throw AuditApiException.conflict(correlationId, "idempotency_conflict",
                    "The Idempotency-Key was already used with a different legal-hold request.");
        }
    }

    @GetMapping("/legal-holds/{holdId}")
    ResponseEntity<AuditLegalHoldResource> getHold(
            @PathVariable UUID holdId,
            HttpServletRequest request) {
        UUID correlationId = AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.AUDIT_HOLD, new AdministrativeResource("audit", holdId), correlationId);
        try {
            AuditLegalHold hold = lifecycle.findHold(actor.tenant(), holdId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Correlation-Id", correlationId.toString())
                    .eTag(etag(hold.revision()))
                    .body(hold(hold));
        } catch (IllegalArgumentException missing) {
            throw AuditApiException.notFound(correlationId);
        }
    }

    @PostMapping("/legal-holds/{holdId}:release")
    ResponseEntity<AuditLegalHoldResource> releaseHold(
            @PathVariable UUID holdId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            HttpServletRequest request) {
        UUID correlationId = AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.AUDIT_HOLD, new AdministrativeResource("audit", holdId), correlationId);
        try {
            AuditLegalHold hold = lifecycle.releaseHold(
                    actor.tenant(), holdId, revision(ifMatch, correlationId), actor.identityId());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Correlation-Id", correlationId.toString())
                    .eTag(etag(hold.revision()))
                    .body(hold(hold));
        } catch (StaleWriteException stale) {
            throw AuditApiException.conflict(correlationId, "stale_revision",
                    "The legal hold changed before release.");
        }
    }

    @PostMapping("/purges")
    ResponseEntity<AuditPurgeResource> createPurge(
            @RequestBody AuditPurgeCreateRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        UUID correlationId = AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.AUDIT_PURGE, AdministrativeResource.collection("audit"), correlationId);
        requireIdempotency(idempotencyKey, correlationId);
        if (body == null || body.archiveSegmentId() == null || body.selection() == null) {
            throw AuditApiException.validation(correlationId, "archiveSegmentId", "required",
                    "archiveSegmentId and selection are required.");
        }
        AuditSelection selection = selection(body.selection(), correlationId);
        try {
            AuditPurgeOperation purge = lifecycle.requestPurge(
                    actor.tenant(), actor.identityId(), body.archiveSegmentId(), selection,
                    required(body.reasonCode(), "reasonCode", correlationId),
                    correlationId, null, idempotencyKey.trim(),
                    fingerprint("purge", selection, body.reasonCode(), null, body.archiveSegmentId()));
            return ResponseEntity.accepted()
                    .header(HttpHeaders.LOCATION, "/api/v1/audit-evidence-lifecycle/purges/" + purge.id())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Correlation-Id", correlationId.toString())
                    .eTag(etag(purge.revision()))
                    .body(purge(purge));
        } catch (IdempotencyConflictException conflict) {
            throw AuditApiException.conflict(correlationId, "idempotency_conflict",
                    "The Idempotency-Key was already used with a different purge request.");
        } catch (IllegalStateException blocked) {
            throw lifecycleState(correlationId, blocked);
        }
    }

    @GetMapping("/purges/{purgeId}")
    ResponseEntity<AuditPurgeResource> getPurge(
            @PathVariable UUID purgeId,
            HttpServletRequest request) {
        UUID correlationId = AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.AUDIT_PURGE, new AdministrativeResource("audit", purgeId), correlationId);
        try {
            AuditPurgeOperation purge = lifecycle.findPurge(actor.tenant(), purgeId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Correlation-Id", correlationId.toString())
                    .eTag(etag(purge.revision()))
                    .body(purge(purge));
        } catch (IllegalArgumentException missing) {
            throw AuditApiException.notFound(correlationId);
        }
    }

    @PostMapping("/purges/{purgeId}:approve")
    ResponseEntity<AuditPurgeResource> approvePurge(
            @PathVariable UUID purgeId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            HttpServletRequest request) {
        UUID correlationId = AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.AUDIT_PURGE, new AdministrativeResource("audit", purgeId), correlationId);
        try {
            AuditPurgeOperation purge = lifecycle.approvePurge(
                    actor.tenant(), purgeId, revision(ifMatch, correlationId), actor.identityId());
            return ResponseEntity.accepted()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Correlation-Id", correlationId.toString())
                    .eTag(etag(purge.revision()))
                    .body(purge(purge));
        } catch (StaleWriteException stale) {
            throw AuditApiException.conflict(correlationId, "stale_revision",
                    "The purge operation changed before approval.");
        } catch (IllegalArgumentException self) {
            throw AuditApiException.conflict(correlationId, "purge_self_approval_denied",
                    "The purge requester cannot approve the same purge operation.");
        } catch (IllegalStateException blocked) {
            throw lifecycleState(correlationId, blocked);
        }
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(actor, permission, resource, Instant.now()).allowed()) {
            throw AuditApiException.forbidden(correlationId);
        }
    }

    private static AuditSelection selection(AuditSelectionRequest value, UUID correlationId) {
        if (value.occurredFrom() == null || value.occurredUntil() == null) {
            throw AuditApiException.validation(correlationId, "selection", "required",
                    "selection.occurredFrom and selection.occurredUntil are required.");
        }
        AuditOutcome outcome = null;
        if (value.outcome() != null) {
            try {
                outcome = AuditOutcome.valueOf(value.outcome().trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException invalid) {
                throw AuditApiException.validation(correlationId, "outcome", "invalid_outcome",
                        "outcome must be SUCCESS, DENIED, or FAILURE.");
            }
        }
        try {
            return new AuditSelection(
                    value.occurredFrom(), value.occurredUntil(), value.actorId(),
                    value.actionType(), value.resourceType(), value.resourceId(),
                    outcome, value.correlationId());
        } catch (IllegalArgumentException invalid) {
            throw AuditApiException.validation(correlationId, "selection", "invalid_selection",
                    "Audit evidence selection is invalid.");
        }
    }

    private static long revision(String ifMatch, UUID correlationId) {
        if (ifMatch == null || !ifMatch.matches("\"rev-[1-9][0-9]*\"")) {
            throw AuditApiException.validation(correlationId, "If-Match", "required",
                    "If-Match must contain the current revision ETag.");
        }
        return Long.parseLong(ifMatch.substring(5, ifMatch.length() - 1));
    }

    private static void requireIdempotency(String key, UUID correlationId) {
        if (key == null || key.isBlank()) {
            throw AuditApiException.validation(correlationId, "Idempotency-Key", "required",
                    "Idempotency-Key is required.");
        }
    }

    private static String required(String value, String field, UUID correlationId) {
        if (value == null || value.isBlank()) {
            throw AuditApiException.validation(correlationId, field, "required", field + " is required.");
        }
        return value.trim();
    }

    private static RequestFingerprint fingerprint(
            String kind,
            AuditSelection selection,
            String reasonCode,
            String caseReference,
            UUID archiveSegmentId) {
        String canonical = String.join("|", kind,
                selection.occurredFrom().toString(), selection.occurredUntil().toString(),
                value(selection.actorId()), value(selection.actionType()), value(selection.resourceType()),
                value(selection.resourceId()), value(selection.outcome()), value(selection.correlationId()),
                value(reasonCode == null ? null : reasonCode.trim()),
                value(caseReference == null ? null : caseReference.trim()),
                value(archiveSegmentId));
        return RequestFingerprint.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static AuditApiException lifecycleState(UUID correlationId, IllegalStateException state) {
        String code = state.getMessage() == null ? "audit_evidence_lifecycle_conflict" : state.getMessage();
        if ("audit_purge_unavailable".equals(code)) {
            return AuditApiException.unavailable(correlationId, code,
                    "Audit purge is not enabled for this deployment.");
        }
        return AuditApiException.conflict(correlationId, code,
                "Audit evidence lifecycle prerequisites are not satisfied.");
    }

    private static String etag(long revision) {
        return "\"rev-" + revision + "\"";
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static AuditSelectionRequest selection(AuditSelection value) {
        return new AuditSelectionRequest(
                value.occurredFrom(), value.occurredUntil(), value.actorId(),
                value.actionType(), value.resourceType(), value.resourceId(),
                value.outcome() == null ? null : value.outcome().name(), value.correlationId());
    }

    private static AuditLegalHoldResource hold(AuditLegalHold value) {
        return new AuditLegalHoldResource(
                value.id(), selection(value.selection()), value.reasonCode(), value.caseReference(),
                value.state().name(), value.createdByIdentityId(), value.releasedByIdentityId(),
                value.correlationId(), value.causationId(), value.revision(),
                value.createdAt(), value.releasedAt(), value.updatedAt());
    }

    private static AuditPurgeResource purge(AuditPurgeOperation value) {
        return new AuditPurgeResource(
                value.id(), value.retentionPolicyVersionId(), value.archiveSegmentId(),
                value.requestedByIdentityId(), value.approvedByIdentityId(),
                selection(value.selection()), value.snapshotRecordedAt(), value.reasonCode(),
                value.state().name(), value.deletedRecordCount(), value.failureCode(),
                value.correlationId(), value.causationId(), value.revision(),
                value.approvedAt(), value.completedAt(), value.createdAt(), value.updatedAt());
    }
}
