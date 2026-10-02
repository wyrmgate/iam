package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassObligation;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassOperation;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassReview;
import io.wyrmgate.iam.administration.domain.AdministrativeBreakGlassReviewOutcome;
import io.wyrmgate.iam.administration.domain.AdministrativeRole;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.administration.domain.AuthenticationAssuranceContext;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdministrativeBreakGlassRepository {

    Optional<AdministrativeBreakGlassOperation> find(
            TenantContext tenant, UUID operationId);

    AdministrativeBreakGlassOperation activate(
            TenantContext tenant,
            UUID operationId,
            UUID actorIdentityId,
            AdministrativeRole role,
            AdministrativeScope scope,
            String reason,
            String incidentReference,
            Instant validUntil,
            AuthenticationAssuranceContext assurance,
            Duration maxAssuranceAge,
            UUID notificationObligationId,
            UUID reviewObligationId,
            UUID correlationId,
            UUID causationId,
            Instant now);

    AdministrativeBreakGlassOperation revoke(
            TenantContext tenant,
            UUID operationId,
            UUID revokedByIdentityId,
            long expectedRevision,
            Instant now);

    List<AdministrativeBreakGlassOperation> list(
            TenantContext tenant,
            Instant afterCreatedAt,
            UUID afterId,
            int limit);

    Optional<AdministrativeBreakGlassReview> findReview(
            TenantContext tenant, UUID operationId);

    AdministrativeBreakGlassReview completeReview(
            TenantContext tenant,
            UUID reviewId,
            UUID operationId,
            UUID reviewerIdentityId,
            long expectedOperationRevision,
            AdministrativeBreakGlassReviewOutcome outcome,
            String summary,
            UUID correlationId,
            UUID causationId,
            Instant now);

    List<AdministrativeBreakGlassObligation> listObligations(
            TenantContext tenant, UUID operationId);
}
