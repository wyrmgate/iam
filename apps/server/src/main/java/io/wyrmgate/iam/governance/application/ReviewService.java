package io.wyrmgate.iam.governance.application;

import io.wyrmgate.iam.governance.domain.ReviewModels.*;
import io.wyrmgate.iam.identity.application.IdentityAccessReferenceQuery;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ReviewService {

    private final ReviewRepository repository;
    private final IdentityAccessReferenceQuery identities;
    private final ReviewWorkSink work;
    private final IdGenerator ids;
    private final TransactionExecutor transactions;

    public ReviewService(
            ReviewRepository repository,
            IdentityAccessReferenceQuery identities,
            ReviewWorkSink work,
            IdGenerator ids,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.identities = Objects.requireNonNull(
                identities, "identities");
        this.work = Objects.requireNonNull(work, "work");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.transactions = Objects.requireNonNull(
                transactions, "transactions");
    }

    public ReviewCampaign createIdentityAccessCampaign(
            TenantContext tenant,
            UUID subjectIdentityId,
            UUID reviewerIdentityId,
            Instant snapshotAt,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(
                subjectIdentityId, "subjectIdentityId");
        Objects.requireNonNull(
                reviewerIdentityId, "reviewerIdentityId");
        Objects.requireNonNull(snapshotAt, "snapshotAt");
        Objects.requireNonNull(now, "now");

        if (subjectIdentityId.equals(reviewerIdentityId)) {
            throw new IllegalArgumentException(
                    "reviewer must differ from subject Identity");
        }
        if (snapshotAt.isAfter(now)) {
            throw new IllegalArgumentException(
                    "snapshotAt must not be in the future");
        }
        if (!identities.identityExists(
                tenant, subjectIdentityId)) {
            throw new IllegalArgumentException(
                    "review subject Identity does not exist");
        }
        if (!identities.identityExists(
                tenant, reviewerIdentityId)) {
            throw new IllegalArgumentException(
                    "reviewer Identity does not exist");
        }

        ReviewCampaign campaign =
                new ReviewCampaign(
                        ids.nextId(),
                        CampaignKind.IDENTITY_ACCESS,
                        subjectIdentityId,
                        reviewerIdentityId,
                        snapshotAt,
                        CampaignState.DRAFT,
                        null,
                        null,
                        0,
                        0,
                        1,
                        now,
                        now,
                        null,
                        null,
                        null);
        return transactions.required(() -> {
            repository.insertCampaign(
                    tenant, campaign);
            return repository.findCampaign(
                            tenant, campaign.id())
                    .orElseThrow();
        });
    }

    public ReviewCampaign startGeneration(
            TenantContext tenant,
            UUID campaignId,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.required(() -> {
            ReviewCampaign generating =
                    repository.startGeneration(
                            tenant,
                            campaignId,
                            expectedRevision,
                            now);
            work.generationRequested(
                    tenant, generating);
            return generating;
        });
    }

    public DecisionResult decide(
            TenantContext tenant,
            UUID reviewItemId,
            UUID reviewerIdentityId,
            DecisionValue decision,
            String reason,
            long expectedRevision,
            Instant now) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(
                reviewItemId, "reviewItemId");
        Objects.requireNonNull(
                reviewerIdentityId, "reviewerIdentityId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(now, "now");

        String normalizedReason =
                normalizeReason(reason);

        return transactions.required(() -> {
            ReviewItem item = repository.findItem(
                            tenant, reviewItemId)
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "ReviewItem does not exist"));
            if (item.state() != ItemState.PENDING) {
                throw new IllegalStateException(
                        "ReviewItem is already decided");
            }
            if (!item.reviewerIdentityId()
                    .equals(reviewerIdentityId)) {
                throw new IllegalArgumentException(
                        "actor is not the assigned reviewer");
            }

            ReviewDecision evidence =
                    new ReviewDecision(
                            ids.nextId(),
                            item.id(),
                            reviewerIdentityId,
                            decision,
                            normalizedReason,
                            now);
            repository.insertDecision(
                    tenant, evidence);
            ReviewItem decided =
                    repository.markItemDecided(
                            tenant,
                            item.id(),
                            expectedRevision,
                            now);
            ReviewCampaign campaign =
                    repository.incrementDecidedCount(
                            tenant,
                            item.reviewCampaignId(),
                            now);

            ReviewRemediation remediation = null;
            if (decision == DecisionValue.REVOKE) {
                remediation =
                        new ReviewRemediation(
                                ids.nextId(),
                                item.id(),
                                item.accessAssignmentId(),
                                RemediationState.PENDING,
                                null,
                                null,
                                1,
                                now,
                                now,
                                null);
                repository.insertRemediation(
                        tenant, remediation);
                work.remediationRequested(
                        tenant, remediation);
            }
            return new DecisionResult(
                    decided,
                    evidence,
                    campaign,
                    remediation);
        });
    }

    private static String normalizeReason(
            String reason) {
        if (reason == null) return null;
        String normalized = reason.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException(
                    "reason exceeds 1000 characters");
        }
        return normalized;
    }

    public record DecisionResult(
            ReviewItem item,
            ReviewDecision decision,
            ReviewCampaign campaign,
            ReviewRemediation remediation) {}
}
