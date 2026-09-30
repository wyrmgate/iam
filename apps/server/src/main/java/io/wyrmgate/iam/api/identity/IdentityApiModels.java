package io.wyrmgate.iam.api.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    record IdentityMergeOperationResource(
            UUID id,
            UUID survivorIdentityId,
            UUID absorbedIdentityId,
            int movedLinkCount,
            int movedPrincipalCount,
            Instant completedAt) {
    }

    record IdentitySplitOperationResource(
            UUID id,
            UUID sourceIdentityId,
            UUID newIdentityId,
            List<UUID> movedSourceRecordIds,
            List<UUID> movedPrincipalIds,
            Instant completedAt) {
    }

    sealed interface CanonicalValueResource permits
            StringCanonicalValueResource,
            BooleanCanonicalValueResource,
            IntegerCanonicalValueResource,
            DecimalCanonicalValueResource,
            DateCanonicalValueResource,
            DateTimeCanonicalValueResource,
            EnumCanonicalValueResource {
    }

    record StringCanonicalValueResource(String type, String value) implements CanonicalValueResource {
    }

    record BooleanCanonicalValueResource(String type, boolean value) implements CanonicalValueResource {
    }

    record IntegerCanonicalValueResource(String type, long value) implements CanonicalValueResource {
    }

    record DecimalCanonicalValueResource(String type, String value) implements CanonicalValueResource {
    }

    record DateCanonicalValueResource(String type, String value) implements CanonicalValueResource {
    }

    record DateTimeCanonicalValueResource(String type, String value) implements CanonicalValueResource {
    }

    record EnumCanonicalValueResource(String type, String key) implements CanonicalValueResource {
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
            boolean hasTrustedValue,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<CanonicalValueResource> values) {
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
