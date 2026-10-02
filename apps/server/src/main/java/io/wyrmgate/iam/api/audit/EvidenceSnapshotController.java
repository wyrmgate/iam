package io.wyrmgate.iam.api.audit;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.audit.AuditApiModels.EvidenceReferenceResource;
import io.wyrmgate.iam.api.audit.AuditApiModels.EvidenceSnapshotPage;
import io.wyrmgate.iam.api.audit.AuditApiModels.EvidenceSnapshotResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.audit.application.EvidenceSnapshotService;
import io.wyrmgate.iam.audit.domain.EvidenceResourceReference;
import io.wyrmgate.iam.audit.domain.EvidenceSnapshot;
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
@RequestMapping("/api/v1/evidence-snapshots")
public final class EvidenceSnapshotController {

    private static final int DEFAULT_LIMIT = 50;

    private final EvidenceSnapshotService snapshots;
    private final AdministrativeAuthorizationService authorization;
    private final AuditCursorCodec cursors;
    private final IdGenerator ids;

    EvidenceSnapshotController(
            EvidenceSnapshotService snapshots,
            AdministrativeAuthorizationService authorization,
            AuditCursorCodec cursors,
            IdGenerator ids) {
        this.snapshots = snapshots;
        this.authorization = authorization;
        this.cursors = cursors;
        this.ids = ids;
    }

    @GetMapping
    ResponseEntity<EvidenceSnapshotPage> list(
            @RequestParam(required = false) String snapshotType,
            @RequestParam(required = false) String subjectResourceType,
            @RequestParam(required = false) UUID subjectResourceId,
            @RequestParam(required = false) UUID correlationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID requestCorrelationId = AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(actor, AdministrativeResource.collection("evidence-snapshot"), requestCorrelationId);

        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > EvidenceSnapshotService.MAX_PAGE_SIZE) {
            throw AuditApiException.validation(
                    requestCorrelationId, "limit", "out_of_range", "limit must be between 1 and 200.");
        }
        EvidenceSnapshotService.Position after = null;
        if (cursor != null) {
            try {
                after = cursors.decodeEvidenceSnapshot(
                        cursor,
                        actor.tenant(),
                        snapshotType,
                        subjectResourceType,
                        subjectResourceId,
                        correlationId);
            } catch (IllegalArgumentException invalid) {
                throw AuditApiException.validation(
                        requestCorrelationId,
                        "cursor",
                        "invalid_cursor",
                        "cursor is invalid or does not match this EvidenceSnapshot search.");
            }
        }
        EvidenceSnapshotService.Page page;
        try {
            page = snapshots.list(
                    actor.tenant(),
                    snapshotType,
                    subjectResourceType,
                    subjectResourceId,
                    correlationId,
                    after,
                    effectiveLimit);
        } catch (IllegalArgumentException invalid) {
            throw AuditApiException.validation(
                    requestCorrelationId,
                    "filter",
                    "invalid_filter",
                    "EvidenceSnapshot search filter is invalid.");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", requestCorrelationId.toString())
                .body(new EvidenceSnapshotPage(
                        page.items().stream().map(EvidenceSnapshotController::resource).toList(),
                        cursors.encodeEvidenceSnapshot(
                                actor.tenant(),
                                snapshotType,
                                subjectResourceType,
                                subjectResourceId,
                                correlationId,
                                page.nextPosition())));
    }

    @GetMapping("/{evidenceSnapshotId}")
    ResponseEntity<EvidenceSnapshotResource> get(
            @PathVariable UUID evidenceSnapshotId,
            HttpServletRequest request) {
        UUID correlationId = AuditApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        requireRead(
                actor,
                new AdministrativeResource("evidence-snapshot", evidenceSnapshotId),
                correlationId);
        EvidenceSnapshot snapshot = snapshots.findById(actor.tenant(), evidenceSnapshotId)
                .orElseThrow(() -> AuditApiException.notFound(correlationId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-Id", correlationId.toString())
                .body(resource(snapshot));
    }

    private void requireRead(
            AuthenticatedAdministrativeActor actor,
            AdministrativeResource resource,
            UUID correlationId) {
        if (!authorization.authorize(
                actor,
                AdministrativePermissions.EVIDENCE_SNAPSHOT_READ,
                resource,
                Instant.now()).allowed()) {
            throw AuditApiException.forbidden(correlationId);
        }
    }

    private static EvidenceSnapshotResource resource(EvidenceSnapshot value) {
        return new EvidenceSnapshotResource(
                value.id(),
                value.occurredAt(),
                value.recordedAt(),
                value.actorId(),
                value.snapshotType(),
                reference(value.subject()),
                reference(value.policy()),
                reference(value.related()),
                value.decisionLabel(),
                value.correlationId(),
                value.causationId());
    }

    private static EvidenceReferenceResource reference(EvidenceResourceReference value) {
        return value == null
                ? null
                : new EvidenceReferenceResource(
                        value.resourceType(),
                        value.resourceId(),
                        value.revision(),
                        value.displayLabel());
    }
}
