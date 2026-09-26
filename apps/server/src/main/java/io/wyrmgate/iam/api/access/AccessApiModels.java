package io.wyrmgate.iam.api.access;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class AccessApiModels {
    private AccessApiModels() {}

    record AccessAssignmentResource(
            UUID id,
            UUID identityId,
            String targetKind,
            UUID roleId,
            UUID entitlementId,
            String principalConstraintKind,
            UUID specificPrincipalId,
            String provenanceKind,
            UUID provenanceRefId,
            String lifecycleState,
            Instant validFrom,
            Instant validUntil,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record AccessAssignmentPage(
            List<AccessAssignmentResource> items,
            String nextCursor) {}

    record EffectiveAccessSummary(
            UUID id,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey,
            int supportCount,
            Instant computedAt,
            long projectionGeneration) {}

    record EffectiveAccessSupportResource(
            UUID accessAssignmentId,
            String pathHash,
            int pathDepth,
            List<UUID> roleVersionPath) {}

    record EffectiveAccessResource(
            UUID id,
            UUID identityId,
            UUID entitlementId,
            String principalConstraintKey,
            int supportCount,
            Instant computedAt,
            long projectionGeneration,
            List<EffectiveAccessSupportResource> supports) {}

    record EffectiveAccessPage(
            List<EffectiveAccessSummary> items,
            String nextCursor) {}

    record FieldError(String field, String code, String message) {}

    record ErrorResponse(
            String code,
            String message,
            UUID correlationId,
            List<FieldError> fieldErrors) {}
}
