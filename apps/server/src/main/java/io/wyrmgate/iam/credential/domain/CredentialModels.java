package io.wyrmgate.iam.credential.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class CredentialModels {
    private CredentialModels() {}

    public enum CredentialKind {
        PASSWORD,
        API_KEY,
        SSH_KEY,
        CERTIFICATE,
        OAUTH_CLIENT_SECRET
    }

    public enum CredentialState {
        SCHEDULED,
        ACTIVE,
        REVOKED,
        EXPIRED,
        COMPROMISED
    }

    public enum RotationState {
        PLANNED,
        CREATING_REPLACEMENT,
        DISTRIBUTING,
        VERIFYING,
        CUTOVER_COMPLETE,
        REVOKING_OLD,
        COMPLETED,
        FAILED,
        MANUAL_REQUIRED,
        FAILED_REMEDIATION
    }

    public record SecretReference(
            String providerType,
            String referenceKey) {
        public SecretReference {
            providerType = requireText(providerType, "providerType", 64);
            referenceKey = requireText(referenceKey, "referenceKey", 512);
        }
    }

    public record Credential(
            UUID id,
            UUID principalId,
            CredentialKind kind,
            SecretReference secretReference,
            CredentialState state,
            Instant validFrom,
            Instant validUntil,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant compromisedAt,
            Instant revokedAt,
            Instant expiredAt) {

        public Credential {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(secretReference, "secretReference");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (validFrom != null && validUntil != null
                    && !validUntil.isAfter(validFrom)) {
                throw new IllegalArgumentException(
                        "validUntil must be after validFrom");
            }
            if (revision < 1) {
                throw new IllegalArgumentException(
                        "revision must be positive");
            }
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException(
                        "updatedAt must not be before createdAt");
            }
            switch (state) {
                case SCHEDULED, ACTIVE -> {
                    if (compromisedAt != null
                            || revokedAt != null
                            || expiredAt != null) {
                        throw new IllegalArgumentException(
                                "non-terminal Credential must not carry terminal timestamps");
                    }
                }
                case COMPROMISED -> {
                    Objects.requireNonNull(
                            compromisedAt, "compromisedAt");
                    if (revokedAt != null || expiredAt != null) {
                        throw new IllegalArgumentException(
                                "COMPROMISED Credential must only carry compromisedAt");
                    }
                }
                case REVOKED -> {
                    Objects.requireNonNull(
                            revokedAt, "revokedAt");
                    if (expiredAt != null) {
                        throw new IllegalArgumentException(
                                "REVOKED Credential must not carry expiredAt");
                    }
                }
                case EXPIRED -> {
                    Objects.requireNonNull(
                            expiredAt, "expiredAt");
                    if (revokedAt != null) {
                        throw new IllegalArgumentException(
                                "EXPIRED Credential must not carry revokedAt");
                    }
                }
            }
        }

        public boolean effectiveAt(Instant at) {
            Objects.requireNonNull(at, "at");
            if (state != CredentialState.ACTIVE) {
                return false;
            }
            if (validFrom != null && at.isBefore(validFrom)) {
                return false;
            }
            return validUntil == null || at.isBefore(validUntil);
        }
    }

    public record CredentialRotation(
            UUID id,
            UUID oldCredentialId,
            UUID replacementCredentialId,
            UUID initiatorIdentityId,
            RotationState state,
            String checkpoint,
            String failureCode,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {
        public CredentialRotation {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(
                    oldCredentialId, "oldCredentialId");
            Objects.requireNonNull(
                    initiatorIdentityId, "initiatorIdentityId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            checkpoint = normalizeOptional(
                    checkpoint, "checkpoint", 256);
            failureCode = normalizeOptional(
                    failureCode, "failureCode", 128);
            if (revision < 1) {
                throw new IllegalArgumentException(
                        "revision must be positive");
            }
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException(
                        "updatedAt must not be before createdAt");
            }
            if (isTerminal(state) && completedAt == null) {
                throw new IllegalArgumentException(
                        "terminal CredentialRotation requires completedAt");
            }
            if (!isTerminal(state) && completedAt != null) {
                throw new IllegalArgumentException(
                        "non-terminal CredentialRotation must not carry completedAt");
            }
        }

        public static boolean isTerminal(
                RotationState state) {
            return state == RotationState.COMPLETED
                    || state == RotationState.FAILED
                    || state == RotationState.MANUAL_REQUIRED
                    || state == RotationState.FAILED_REMEDIATION;
        }
    }

    private static String requireText(
            String value,
            String name,
            int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " exceeds maximum length");
        }
        return normalized;
    }

    private static String normalizeOptional(
            String value,
            String name,
            int maxLength) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " exceeds maximum length");
        }
        return normalized;
    }
}
