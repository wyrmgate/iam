package io.wyrmgate.iam.api.credential;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class CredentialApiModels {
    private CredentialApiModels() {}

    record SecretReferenceResource(
            String providerType,
            String referenceKey) {}

    record CredentialResource(
            UUID id,
            UUID principalId,
            String kind,
            SecretReferenceResource secretReference,
            String lifecycleState,
            Instant validFrom,
            Instant validUntil,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant compromisedAt,
            Instant revokedAt,
            Instant expiredAt) {}

    record CredentialPage(
            List<CredentialResource> items,
            String nextCursor) {}

    record CredentialRotationResource(
            UUID id,
            UUID oldCredentialId,
            UUID replacementCredentialId,
            UUID initiatorIdentityId,
            String processState,
            String failureCode,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {}

    record CredentialRotationPage(
            List<CredentialRotationResource> items,
            String nextCursor) {}

    record FieldError(String field, String code, String message) {}

    record ErrorResponse(
            String code,
            String message,
            UUID correlationId,
            List<FieldError> fieldErrors) {}
}
