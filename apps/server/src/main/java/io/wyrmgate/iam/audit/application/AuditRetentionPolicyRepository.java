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
            Duration exportArtifactRetention,
            Duration archiveEligibleAfter,
            Duration minimumOnlineRetention,
            Duration minimumArchiveRetention,
            Instant effectiveFrom,
            Instant createdAt);

    Optional<AuditRetentionPolicyVersion> findById(TenantContext tenant, UUID id);

    Optional<AuditRetentionPolicyVersion> findByVersion(TenantContext tenant, long version);

    Optional<AuditRetentionPolicyVersion> findCurrent(TenantContext tenant, Instant at);
}
