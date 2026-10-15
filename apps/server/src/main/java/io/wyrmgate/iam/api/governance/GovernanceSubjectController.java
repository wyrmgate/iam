package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.api.governance.GovernanceExceptionApiModels.GovernanceExceptionResource;
import io.wyrmgate.iam.api.governance.GovernanceReviewApiModels.ReviewCampaignResource;
import io.wyrmgate.iam.api.security.ControlPlaneActorRequestContext;
import io.wyrmgate.iam.governance.application.GovernanceSubjectQuery;
import io.wyrmgate.iam.governance.application.GovernanceSubjectQuery.Position;
import io.wyrmgate.iam.governance.domain.GovernanceExceptionModels.GovernanceException;
import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewCampaign;
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
@RequestMapping("/api/v1/governance/subjects/{subjectIdentityId}")
public final class GovernanceSubjectController {
    record CampaignPage(List<ReviewCampaignResource> items, String nextCursor) {}
    record ExceptionPage(List<GovernanceExceptionResource> items, String nextCursor) {}

    private static final int DEFAULT_LIMIT = 50;
    private final GovernanceSubjectQuery queries;
    private final AdministrativeAuthorizationService authorization;
    private final IdGenerator ids;
    private final GovernanceSubjectCursorCodec cursors;

    public GovernanceSubjectController(
            GovernanceSubjectQuery queries,
            AdministrativeAuthorizationService authorization,
            IdGenerator ids,
            GovernanceSubjectCursorCodec cursors) {
        this.queries = queries;
        this.authorization = authorization;
        this.ids = ids;
        this.cursors = cursors;
    }

    @GetMapping("/review-campaigns")
    public ResponseEntity<CampaignPage> campaigns(
            @PathVariable UUID subjectIdentityId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = GovernanceApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.REVIEW_CAMPAIGN_READ, "review-campaign", correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        Position after = decode(cursor, "subject-review-campaign", actor, subjectIdentityId, correlationId);
        var page = queries.campaigns(actor.tenant(), subjectIdentityId, after, effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CampaignPage(
                        page.items().stream().map(GovernanceSubjectController::campaignResource).toList(),
                        cursors.encode("subject-review-campaign", actor.tenant(), subjectIdentityId, page.nextPosition())));
    }

    @GetMapping("/exceptions")
    public ResponseEntity<ExceptionPage> exceptions(
            @PathVariable UUID subjectIdentityId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        UUID correlationId = GovernanceApiRequestContext.resolveCorrelationId(request, ids);
        AuthenticatedAdministrativeActor actor = ControlPlaneActorRequestContext.require(request);
        require(actor, AdministrativePermissions.GOVERNANCE_EXCEPTION_READ, "governance-exception", correlationId);
        int effectiveLimit = validateLimit(limit, correlationId);
        Position after = decode(cursor, "subject-governance-exception", actor, subjectIdentityId, correlationId);
        var page = queries.exceptions(actor.tenant(), subjectIdentityId, after, effectiveLimit);
        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ExceptionPage(
                        page.items().stream().map(GovernanceSubjectController::exceptionResource).toList(),
                        cursors.encode("subject-governance-exception", actor.tenant(), subjectIdentityId, page.nextPosition())));
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            io.wyrmgate.iam.administration.domain.AdministrativePermission permission,
            String resourceType,
            UUID correlationId) {
        if (!authorization.authorize(actor, permission, AdministrativeResource.collection(resourceType), Instant.now()).allowed()) {
            throw GovernanceApiException.forbidden(correlationId);
        }
    }

    private Position decode(
            String cursor,
            String kind,
            AuthenticatedAdministrativeActor actor,
            UUID subjectIdentityId,
            UUID correlationId) {
        if (cursor == null) return null;
        try { return cursors.decode(cursor, kind, actor.tenant(), subjectIdentityId); }
        catch (IllegalArgumentException invalid) {
            throw GovernanceApiException.validation(correlationId, "cursor", "invalid_cursor", "cursor is invalid or malformed.");
        }
    }

    private static int validateLimit(Integer limit, UUID correlationId) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > 200) throw GovernanceApiException.validation(
                correlationId, "limit", "out_of_range", "limit must be between 1 and 200.");
        return value;
    }

    private static ReviewCampaignResource campaignResource(ReviewCampaign value) {
        return new ReviewCampaignResource(
                value.id(), value.kind().name(), value.subjectIdentityId(), value.reviewerIdentityId(),
                value.snapshotAt(), value.state().name(), value.generatedItemCount(), value.decidedItemCount(),
                value.revision(), value.createdAt(), value.updatedAt(), value.activatedAt(), value.completedAt(), value.failureCode());
    }

    private static GovernanceExceptionResource exceptionResource(GovernanceException value) {
        return new GovernanceExceptionResource(
                value.id(), value.scopeKind().name(), value.subjectIdentityId(), value.sodRuleId(),
                value.requesterIdentityId(), value.businessReason(), value.validFrom(), value.validUntil(),
                value.lifecycleState().name(), value.approvalCaseId(), value.predecessorExceptionId(),
                value.revision(), value.createdAt(), value.updatedAt(), value.approvedAt(), value.rejectedAt(),
                value.revokedAt(), value.expiredAt());
    }
}
