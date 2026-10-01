package io.wyrmgate.iam.api.administration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class AdministrationApiModels {
    private AdministrationApiModels() {}

    record ScopeResource(
            String type,
            String resourceType,
            UUID resourceId,
            String scopeKey) {}

    record AdministrativeRoleResource(
            UUID id,
            String code,
            String name,
            List<String> permissions,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record AdministrativeRolePage(
            List<AdministrativeRoleResource> items,
            String nextCursor) {}

    record AdministrativeGrantResource(
            UUID id,
            UUID actorIdentityId,
            UUID roleId,
            ScopeResource scope,
            String state,
            Instant validFrom,
            Instant validUntil,
            boolean grantable,
            boolean delegable,
            UUID authorityBasisGrantId,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record AdministrativeGrantPage(
            List<AdministrativeGrantResource> items,
            String nextCursor) {}

    record AdministrativeDelegationResource(
            UUID id,
            UUID delegateIdentityId,
            UUID delegatorIdentityId,
            UUID sourceGrantId,
            UUID roleId,
            ScopeResource scope,
            String state,
            Instant validFrom,
            Instant validUntil,
            UUID createdByIdentityId,
            UUID revokedByIdentityId,
            Instant revokedAt,
            UUID correlationId,
            UUID causationId,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record AdministrativeDelegationPage(
            List<AdministrativeDelegationResource> items,
            String nextCursor) {}

    record AdministrativeElevationResource(
            UUID id,
            UUID beneficiaryIdentityId,
            UUID initiatorIdentityId,
            UUID authorityBasisGrantId,
            UUID roleId,
            ScopeResource scope,
            Instant validFrom,
            Instant validUntil,
            String state,
            UUID approvalCaseId,
            Instant activatedAt,
            Instant deniedAt,
            Instant cancelledAt,
            Instant revokedAt,
            UUID correlationId,
            UUID causationId,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record AdministrativeElevationPage(
            List<AdministrativeElevationResource> items,
            String nextCursor) {}

    record AdministrativeBreakGlassResource(
            UUID id,
            UUID actorIdentityId,
            UUID roleId,
            ScopeResource scope,
            String reason,
            String incidentReference,
            Instant validFrom,
            Instant validUntil,
            String activationAssuranceLevel,
            Instant activationAuthenticatedAt,
            Instant activationStepUpAt,
            String state,
            Instant activatedAt,
            UUID revokedByIdentityId,
            Instant revokedAt,
            UUID correlationId,
            UUID causationId,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record AdministrativeBreakGlassPage(
            List<AdministrativeBreakGlassResource> items,
            String nextCursor) {}

    record FieldError(String field, String code, String message) {}

    record ErrorResponse(
            String code,
            String message,
            UUID correlationId,
            List<FieldError> fieldErrors) {}
}
