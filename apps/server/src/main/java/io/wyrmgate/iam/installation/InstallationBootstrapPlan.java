package io.wyrmgate.iam.installation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Deployment-local durable bootstrap plan. Contains stable identifiers and non-secret inputs only.
 */
public record InstallationBootstrapPlan(
        InstallationBootstrapState state,
        UUID tenantId,
        String tenantDisplayName,
        UUID identityId,
        String identityDisplayName,
        UUID consoleApplicationId,
        UUID consoleTargetId,
        UUID consoleSsoRegistrationId,
        String consoleClientId,
        String consoleRedirectUri,
        UUID principalId,
        String principalKey,
        UUID credentialId,
        UUID correlationId,
        int bootstrapVersion,
        Instant startedAt,
        Instant updatedAt,
        Instant sealedAt) {

    public InstallationBootstrapPlan {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(tenantId, "tenantId");
        tenantDisplayName = requireText(tenantDisplayName, "tenantDisplayName", 512);
        Objects.requireNonNull(identityId, "identityId");
        identityDisplayName = requireText(identityDisplayName, "identityDisplayName", 512);
        Objects.requireNonNull(consoleApplicationId, "consoleApplicationId");
        Objects.requireNonNull(consoleTargetId, "consoleTargetId");
        Objects.requireNonNull(consoleSsoRegistrationId, "consoleSsoRegistrationId");
        consoleClientId = requireText(consoleClientId, "consoleClientId", 200);
        consoleRedirectUri = requireText(consoleRedirectUri, "consoleRedirectUri", 2048);
        Objects.requireNonNull(principalId, "principalId");
        principalKey = requireText(principalKey, "principalKey", 512);
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (bootstrapVersion < 1) {
            throw new IllegalArgumentException("bootstrapVersion must be positive");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("updatedAt must not be before startedAt");
        }
        if (state == InstallationBootstrapState.SEALED && sealedAt == null) {
            throw new IllegalArgumentException("SEALED bootstrap requires sealedAt");
        }
        if (state != InstallationBootstrapState.SEALED && sealedAt != null) {
            throw new IllegalArgumentException("non-SEALED bootstrap must not carry sealedAt");
        }
    }

    public InstallationBootstrapPlan withState(
            InstallationBootstrapState next,
            Instant now) {
        return new InstallationBootstrapPlan(
                next,
                tenantId,
                tenantDisplayName,
                identityId,
                identityDisplayName,
                consoleApplicationId,
                consoleTargetId,
                consoleSsoRegistrationId,
                consoleClientId,
                consoleRedirectUri,
                principalId,
                principalKey,
                credentialId,
                correlationId,
                bootstrapVersion,
                startedAt,
                now,
                next == InstallationBootstrapState.SEALED ? now : null);
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds maximum length");
        }
        return normalized;
    }
}
