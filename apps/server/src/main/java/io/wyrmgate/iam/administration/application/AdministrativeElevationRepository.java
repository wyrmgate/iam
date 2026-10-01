package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeElevation;
import io.wyrmgate.iam.administration.domain.AdministrativeElevationState;
import io.wyrmgate.iam.administration.domain.AdministrativeScope;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdministrativeElevationRepository {

    Optional<AdministrativeElevation> find(TenantContext tenant, UUID elevationId);

    AdministrativeElevation insert(
            TenantContext tenant,
            UUID id,
            UUID beneficiaryIdentityId,
            UUID initiatorIdentityId,
            UUID authorityBasisGrantId,
            UUID roleId,
            AdministrativeScope scope,
            Instant validFrom,
            Instant validUntil,
            String requestFingerprint,
            UUID correlationId,
            UUID causationId,
            Instant now);

    AdministrativeElevation bindApproval(
            TenantContext tenant,
            UUID elevationId,
            long expectedRevision,
            UUID approvalCaseId,
            String approvalPlanFingerprint,
            Instant now);

    AdministrativeElevation transition(
            TenantContext tenant,
            UUID elevationId,
            AdministrativeElevationState expectedState,
            AdministrativeElevationState targetState,
            long expectedRevision,
            Instant now);

    List<AdministrativeElevation> list(
            TenantContext tenant,
            Instant afterCreatedAt,
            UUID afterId,
            int limit);
}
