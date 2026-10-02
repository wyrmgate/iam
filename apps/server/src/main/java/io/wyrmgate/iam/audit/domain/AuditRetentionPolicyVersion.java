package io.wyrmgate.iam.audit.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable tenant-scoped ADR-0034 retention-policy version. */
public record AuditRetentionPolicyVersion(
        UUID id,
        long version,
        Duration exportArtifactRetention,
        Duration archiveEligibleAfter,
        Duration minimumOnlineRetention,
        Duration minimumArchiveRetention,
        Instant effectiveFrom,
        Instant createdAt) {

    public AuditRetentionPolicyVersion {
        Objects.requireNonNull(id, "id");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        exportArtifactRetention = positive(exportArtifactRetention, "exportArtifactRetention");
        archiveEligibleAfter = positive(archiveEligibleAfter, "archiveEligibleAfter");
        minimumOnlineRetention = positive(minimumOnlineRetention, "minimumOnlineRetention");
        minimumArchiveRetention = positive(minimumArchiveRetention, "minimumArchiveRetention");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
