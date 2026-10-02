package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditArchiveSegment;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditArchiveRepository {

    AuditArchiveSegment createOrFind(
            TenantContext tenant,
            UUID segmentId,
            UUID retentionPolicyVersionId,
            Instant occurredFrom,
            Instant occurredUntil,
            Instant snapshotRecordedAt,
            String schemaVersion,
            UUID correlationId,
            UUID causationId,
            Instant now);

    Optional<AuditArchiveSegment> find(TenantContext tenant, UUID segmentId);

    AuditArchiveSegment beginRun(
            TenantContext tenant,
            UUID segmentId,
            long expectedRevision,
            Instant now);

    AuditArchiveSegment checkpoint(
            TenantContext tenant,
            UUID segmentId,
            long expectedRevision,
            Instant continuationOccurredAt,
            UUID continuationId,
            long recordCount,
            long byteCount,
            Instant now);

    AuditArchiveSegment complete(
            TenantContext tenant,
            UUID segmentId,
            long expectedRevision,
            String artifactReference,
            long recordCount,
            long byteCount,
            String sha256Hex,
            Instant verifiedAt,
            Instant minimumRetainUntil,
            Instant now);

    AuditArchiveSegment fail(
            TenantContext tenant,
            UUID segmentId,
            long expectedRevision,
            String failureCode,
            Instant now);

    List<AuditRecord> findSourcePage(
            TenantContext tenant,
            Instant occurredFrom,
            Instant occurredUntil,
            Instant snapshotRecordedAt,
            Instant afterOccurredAt,
            UUID afterId,
            int limit);
}
