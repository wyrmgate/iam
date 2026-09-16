package io.wyrmgate.iam.api.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class IdentityApiModels {

    private IdentityApiModels() {
    }

    record ProfileResource(String kind) {
    }

    record IdentityResource(
            UUID id,
            String type,
            ProfileResource profile,
            String lifecycleState,
            String displayName,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
    }

    record IdentityPage(List<IdentityResource> items, String nextCursor) {
    }

    record CanonicalAttributeResource(
            UUID definitionId,
            UUID definitionVersionId,
            String key,
            String classification,
            String type,
            String cardinality,
            String resolutionStatus,
            long valueRevision,
            String visibility,
            boolean hasTrustedValue) {
    }

    record CanonicalAttributePage(List<CanonicalAttributeResource> items, String nextCursor) {
    }

    record FieldError(String field, String code, String message) {
    }

    record ErrorResponse(
            String code,
            String message,
            UUID correlationId,
            List<FieldError> fieldErrors) {
    }
}
