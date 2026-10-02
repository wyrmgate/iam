package io.wyrmgate.iam.api.administration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class AdministrationApiModels {
    private AdministrationApiModels() {}

    record PermissionResource(String resourceType, String action, String key) {}
    record ScopeResource(String type, String resourceType, UUID resourceId, String scopeKey) {}

    record AdministrativeRoleResource(
            UUID id, String code, String name, List<PermissionResource> permissions,
            long revision, Instant createdAt, Instant updatedAt) {}
    record AdministrativeGrantResource(
            UUID id, UUID actorIdentityId, UUID roleId, ScopeResource scope, String state,
            Instant validFrom, Instant validUntil, boolean grantable, boolean delegable,
            UUID authorityBasisGrantId, long revision, Instant createdAt, Instant updatedAt) {}
    record AdministrativeDelegationResource(
            UUID id, UUID delegateIdentityId, UUID delegatorIdentityId, UUID sourceGrantId,
            UUID roleId, ScopeResource scope, String state, Instant validFrom, Instant validUntil,
            UUID createdByIdentityId, UUID revokedByIdentityId, Instant revokedAt,
            UUID correlationId, UUID causationId, long revision, Instant createdAt, Instant updatedAt) {}
    record AdministrativeElevationResource(
            UUID id, UUID beneficiaryIdentityId, UUID initiatorIdentityId, UUID authorityBasisGrantId,
            UUID roleId, ScopeResource scope, Instant validFrom, Instant validUntil, String state,
            UUID approvalCaseId, Instant activatedAt, Instant deniedAt, Instant cancelledAt,
            Instant revokedAt, UUID correlationId, UUID causationId, long revision,
            Instant createdAt, Instant updatedAt) {}
    record AdministrativeBreakGlassResource(
            UUID id, UUID actorIdentityId, UUID roleId, ScopeResource scope,
            Instant validFrom, Instant validUntil, String state, String incidentReference,
            long revision, Instant createdAt, Instant updatedAt) {}

    record AdministrativeBreakGlassReviewResource(
            UUID id, UUID breakGlassOperationId, UUID reviewerIdentityId,
            String outcome, String summary, Instant reviewedAt,
            UUID correlationId, UUID causationId, Instant createdAt) {}


    record RolePage(List<AdministrativeRoleResource> items, String nextCursor) {}
    record GrantPage(List<AdministrativeGrantResource> items, String nextCursor) {}
    record DelegationPage(List<AdministrativeDelegationResource> items, String nextCursor) {}
    record ElevationPage(List<AdministrativeElevationResource> items, String nextCursor) {}
    record BreakGlassPage(List<AdministrativeBreakGlassResource> items, String nextCursor) {}

    record FieldError(String field, String code, String message) {}
    record ErrorResponse(String code, String message, UUID correlationId, List<FieldError> fieldErrors) {}
}
