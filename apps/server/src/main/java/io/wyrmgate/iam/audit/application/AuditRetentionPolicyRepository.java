package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditRetentionPolicyVersion;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuditRetentionPolicyRepository {

    AuditRetentionPolicyVersion create(
            TenantContext tenant,
            UUID id,
            long version,
            Duration exportArtifactLifetime,
            Duration archiveEligibilityAge,
            Duration minimumOnlineRecordRetention,
            Duration minimumArchiveRetention,
            Instant effectiveFrom,
            UUID correlationId,
            UUID causationId,
            Instant createdAt);

    Optional<AuditRetentionPolicyVersion> findEffective(
            TenantContext tenant,
            Instant at);
}
