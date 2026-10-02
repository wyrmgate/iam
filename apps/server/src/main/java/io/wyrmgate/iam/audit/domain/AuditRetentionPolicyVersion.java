package io.wyrmgate.iam.audit.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable Audit-owned retention-policy version. */
public record AuditRetentionPolicyVersion(
        UUID id,
        long version,
        Duration exportArtifactLifetime,
        Duration archiveEligibilityAge,
        Duration minimumOnlineRecordRetention,
        Duration minimumArchiveRetention,
        Instant effectiveFrom,
        UUID correlationId,
        UUID causationId,
        Instant createdAt) {

    public AuditRetentionPolicyVersion {
        Objects.requireNonNull(id, "id");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        exportArtifactLifetime = positive(exportArtifactLifetime, "exportArtifactLifetime");
        archiveEligibilityAge = positive(archiveEligibilityAge, "archiveEligibilityAge");
        minimumOnlineRecordRetention = positive(minimumOnlineRecordRetention, "minimumOnlineRecordRetention");
        minimumArchiveRetention = positive(minimumArchiveRetention, "minimumArchiveRetention");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean effectiveAt(Instant instant) {
        return !effectiveFrom.isAfter(Objects.requireNonNull(instant, "instant"));
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
