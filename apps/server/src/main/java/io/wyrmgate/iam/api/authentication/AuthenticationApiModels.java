package io.wyrmgate.iam.api.authentication;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class AuthenticationApiModels {
    private AuthenticationApiModels() {}

    record AuthenticationClientResource(
            UUID id,
            String clientId,
            String displayName,
            String clientType,
            List<URI> redirectUris,
            List<URI> postLogoutRedirectUris,
            Set<String> scopes,
            String state,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record AuthenticationClientPage(
            List<AuthenticationClientResource> items,
            String nextCursor) {}

    record AuthenticationLoginBindingResource(
            UUID id,
            String loginIdentifier,
            UUID principalId,
            UUID identityId,
            String state,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record AuthenticationLoginBindingPage(
            List<AuthenticationLoginBindingResource> items,
            String nextCursor) {}

    record AuthenticationSessionResource(
            UUID id,
            UUID identityId,
            UUID principalId,
            String assurance,
            String state,
            Instant authenticatedAt,
            Instant lastSeenAt,
            Instant expiresAt,
            Instant revokedAt,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record AuthenticationSessionPage(
            List<AuthenticationSessionResource> items,
            String nextCursor) {}

    record FieldError(String field, String code, String message) {}
    record ErrorResource(String code, String message, UUID correlationId, List<FieldError> fieldErrors) {}
}