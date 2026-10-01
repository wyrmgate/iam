package io.wyrmgate.iam.api.governance;

import io.wyrmgate.iam.administration.application.AdministrativeAuthorizationService;
import io.wyrmgate.iam.administration.application.AdministrativeResource;
import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.AdministrativePermission;
import io.wyrmgate.iam.administration.domain.AdministrativePermissions;
import io.wyrmgate.iam.audit.application.AuditRecordDraft;
import io.wyrmgate.iam.audit.application.SecurityAuditPort;
import io.wyrmgate.iam.audit.domain.AuditOutcome;
import io.wyrmgate.iam.governance.application.ReviewQueryModels.ItemEvidence;
import io.wyrmgate.iam.governance.application.ReviewQueryService;
import io.wyrmgate.iam.governance.application.ReviewRepository;
import io.wyrmgate.iam.governance.application.ReviewService;
import io.wyrmgate.iam.governance.domain.ReviewModels.CampaignState;
import io.wyrmgate.iam.governance.domain.ReviewModels.DecisionValue;
import io.wyrmgate.iam.governance.domain.ReviewModels.ItemState;
import io.wyrmgate.iam.governance.domain.ReviewModels.ReviewCampaign;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.Registration;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.StaleWriteException;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GovernanceReviewApiMutationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(GovernanceReviewApiMutationService.class);

    private final AdministrativeAuthorizationService authorization;
    private final ReviewService reviews;
    private final ReviewRepository repository;
    private final ReviewQueryService queries;
    private final JdbcIdempotencyRepository idempotency;
    private final TransactionExecutor transactions;
    private final SecurityAuditPort audit;
    private final IdGenerator ids;

    GovernanceReviewApiMutationService(
            AdministrativeAuthorizationService authorization,
            ReviewService reviews,
            ReviewRepository repository,
            ReviewQueryService queries,
            JdbcIdempotencyRepository idempotency,
            TransactionExecutor transactions,
            SecurityAuditPort audit,
            IdGenerator ids) {
        this.authorization = Objects.requireNonNull(
                authorization, "authorization");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.idempotency = Objects.requireNonNull(
                idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    ReviewCampaign createCampaign(
            AuthenticatedAdministrativeActor actor,
            UUID subjectIdentityId,
            UUID reviewerIdentityId,
            Instant snapshotAt,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        try {
            ReviewCampaign result = transactions.required(() -> {
                require(
                        actor,
                        AdministrativePermissions.REVIEW_CAMPAIGN_CREATE,
                        AdministrativeResource.collection("review-campaign"),
                        now,
                        correlationId);
                Registration registration = register(
                        actor,
                        "api.governance.review-campaign.create.v1",
                        key,
                        fingerprint,
                        now);
                if (registration.kind() == RegistrationKind.REPLAY) {
                    return replayCampaign(
                            actor, registration, correlationId);
                }
                ReviewCampaign created =
                        reviews.createIdentityAccessCampaign(
                                actor.tenant(),
                                subjectIdentityId,
                                reviewerIdentityId,
                                snapshotAt,
                                now);
                complete(
                        actor,
                        "api.governance.review-campaign.create.v1",
                        key,
                        fingerprint,
                        "review-campaign",
                        created.id(),
                        now);
                return created;
            });
            recordOutcome(
                    actor,
                    "review-campaign",
                    result.id(),
                    "review-campaign:create",
                    AuditOutcome.SUCCESS,
                    now,
                    correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    "review-campaign",
                    null,
                    "review-campaign:create",
                    auditOutcome(failure),
                    now,
                    correlationId);
            throw failure;
        }
    }

    ReviewCampaign startCampaign(
            AuthenticatedAdministrativeActor actor,
            UUID campaignId,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        try {
            ReviewCampaign result = transactions.required(() -> {
                ReviewCampaign existing =
                        repository.findCampaign(
                                        actor.tenant(), campaignId)
                                .orElseThrow(() ->
                                        GovernanceApiException.notFound(
                                                correlationId));
                require(
                        actor,
                        AdministrativePermissions.REVIEW_CAMPAIGN_START,
                        new AdministrativeResource(
                                "review-campaign",
                                existing.id()),
                        now,
                        correlationId);
                Registration registration = register(
                        actor,
                        "api.governance.review-campaign.start.v1",
                        key,
                        fingerprint,
                        now);
                if (registration.kind() == RegistrationKind.REPLAY) {
                    return replayCampaign(
                            actor, registration, correlationId);
                }
                if (existing.revision() != expectedRevision) {
                    throw new StaleWriteException(
                            "review-campaign",
                            campaignId,
                            expectedRevision);
                }
                if (existing.state() != CampaignState.DRAFT) {
                    throw GovernanceApiException.conflict(
                            correlationId,
                            "review_campaign_not_draft",
                            "Only a DRAFT ReviewCampaign can start generation.");
                }
                ReviewCampaign started =
                        reviews.startGeneration(
                                actor.tenant(),
                                campaignId,
                                expectedRevision,
                                now);
                complete(
                        actor,
                        "api.governance.review-campaign.start.v1",
                        key,
                        fingerprint,
                        "review-campaign",
                        started.id(),
                        now);
                return started;
            });
            recordOutcome(
                    actor,
                    "review-campaign",
                    campaignId,
                    "review-campaign:start",
                    AuditOutcome.SUCCESS,
                    now,
                    correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    "review-campaign",
                    campaignId,
                    "review-campaign:start",
                    auditOutcome(failure),
                    now,
                    correlationId);
            throw failure;
        }
    }

    ItemEvidence decide(
            AuthenticatedAdministrativeActor actor,
            UUID reviewItemId,
            DecisionValue decision,
            String reason,
            long expectedRevision,
            String key,
            RequestFingerprint fingerprint,
            Instant now,
            UUID correlationId) {
        String auditAction = decision == DecisionValue.KEEP
                ? "review-item:keep"
                : "review-item:revoke";
        try {
            ItemEvidence result = transactions.required(() -> {
                var existing = repository.findItem(
                                actor.tenant(), reviewItemId)
                        .orElseThrow(() ->
                                GovernanceApiException.notFound(
                                        correlationId));
                if (!existing.reviewerIdentityId()
                        .equals(actor.identityId())) {
                    throw GovernanceApiException.forbidden(
                            correlationId);
                }
                Registration registration = register(
                        actor,
                        "api.governance.review-item.decision.v1",
                        key,
                        fingerprint,
                        now);
                if (registration.kind() == RegistrationKind.REPLAY) {
                    return replayItem(
                            actor, registration, correlationId);
                }
                if (existing.revision() != expectedRevision) {
                    throw new StaleWriteException(
                            "review-item",
                            reviewItemId,
                            expectedRevision);
                }
                if (existing.state() != ItemState.PENDING) {
                    throw GovernanceApiException.conflict(
                            correlationId,
                            "review_item_already_decided",
                            "The ReviewItem already has an immutable decision.");
                }
                reviews.decide(
                        actor.tenant(),
                        reviewItemId,
                        actor.identityId(),
                        decision,
                        reason,
                        expectedRevision,
                        now);
                complete(
                        actor,
                        "api.governance.review-item.decision.v1",
                        key,
                        fingerprint,
                        "review-item",
                        reviewItemId,
                        now);
                return queries.itemEvidence(
                                actor.tenant(),
                                reviewItemId)
                        .orElseThrow();
            });
            recordOutcome(
                    actor,
                    "review-item",
                    reviewItemId,
                    auditAction,
                    AuditOutcome.SUCCESS,
                    now,
                    correlationId);
            return result;
        } catch (RuntimeException failure) {
            recordOutcome(
                    actor,
                    "review-item",
                    reviewItemId,
                    auditAction,
                    auditOutcome(failure),
                    now,
                    correlationId);
            throw failure;
        }
    }

    private static AuditOutcome auditOutcome(RuntimeException failure) {
        return failure instanceof GovernanceApiException api
                        && api.status() == org.springframework.http.HttpStatus.FORBIDDEN
                ? AuditOutcome.DENIED
                : AuditOutcome.FAILURE;
    }

    private void recordOutcome(
            AuthenticatedAdministrativeActor actor,
            String resourceType,
            UUID resourceId,
            String actionType,
            AuditOutcome outcome,
            Instant occurredAt,
            UUID correlationId) {
        try {
            audit.append(
                    actor.tenant(),
                    new AuditRecordDraft(
                            ids.nextId(),
                            occurredAt,
                            actor.identityId(),
                            actionType,
                            resourceType,
                            resourceId,
                            outcome,
                            correlationId,
                            null));
        } catch (RuntimeException auditFailure) {
            LOG.warn(
                    "Governance review AuditRecord append failed; correlationId={} actionType={} outcome={}",
                    correlationId,
                    actionType,
                    outcome);
        }
    }

    private Registration register(
            AuthenticatedAdministrativeActor actor,
            String namespace,
            String key,
            RequestFingerprint fingerprint,
            Instant now) {
        return idempotency.register(
                actor.tenant(),
                namespace,
                key,
                fingerprint,
                now,
                null);
    }

    private void complete(
            AuthenticatedAdministrativeActor actor,
            String namespace,
            String key,
            RequestFingerprint fingerprint,
            String resourceType,
            UUID resourceId,
            Instant now) {
        idempotency.complete(
                actor.tenant(),
                namespace,
                key,
                fingerprint,
                resourceType,
                resourceId,
                now);
    }

    private ReviewCampaign replayCampaign(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        requireCompleted(
                registration,
                "review-campaign",
                correlationId);
        return repository.findCampaign(
                        actor.tenant(),
                        registration.resourceId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "idempotent ReviewCampaign result no longer exists"));
    }

    private ItemEvidence replayItem(
            AuthenticatedAdministrativeActor actor,
            Registration registration,
            UUID correlationId) {
        requireCompleted(
                registration,
                "review-item",
                correlationId);
        return queries.itemEvidence(
                        actor.tenant(),
                        registration.resourceId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "idempotent ReviewItem result no longer exists"));
    }

    private static void requireCompleted(
            Registration registration,
            String resourceType,
            UUID correlationId) {
        if (!"COMPLETED".equals(
                registration.operationState())) {
            throw GovernanceApiException.conflict(
                    correlationId,
                    "idempotency_in_progress",
                    "The same idempotency key is already being processed.");
        }
        if (!resourceType.equals(
                    registration.resourceType())
                || registration.resourceId() == null) {
            throw new IllegalStateException(
                    "completed Governance review idempotency result is invalid");
        }
    }

    private void require(
            AuthenticatedAdministrativeActor actor,
            AdministrativePermission permission,
            AdministrativeResource resource,
            Instant now,
            UUID correlationId) {
        if (!authorization.authorize(
                actor, permission, resource, now).allowed()) {
            throw GovernanceApiException.forbidden(
                    correlationId);
        }
    }
}
