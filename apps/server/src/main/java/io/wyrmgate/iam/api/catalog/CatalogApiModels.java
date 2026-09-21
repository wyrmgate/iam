package io.wyrmgate.iam.api.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class CatalogApiModels {
    private CatalogApiModels() {}

    record ApplicationResource(
            UUID id, String code, String name, String lifecycleState,
            long revision, Instant createdAt, Instant updatedAt) {}

    record ApplicationTargetResource(
            UUID id, UUID applicationId, String code, String lifecycleState,
            long revision, Instant createdAt, Instant updatedAt) {}

    record EntitlementResource(
            UUID id, UUID applicationId, UUID applicationTargetId,
            String code, String nativeKey, String entitlementType,
            String lifecycleState, long revision, Instant createdAt, Instant updatedAt) {}

    record ApplicationPage(List<ApplicationResource> items, String nextCursor) {}
    record ApplicationTargetPage(List<ApplicationTargetResource> items, String nextCursor) {}
    record EntitlementPage(List<EntitlementResource> items, String nextCursor) {}
    record FieldError(String field, String code, String message) {}
    record ErrorResponse(
            String code, String message, UUID correlationId, List<FieldError> fieldErrors) {}
}
