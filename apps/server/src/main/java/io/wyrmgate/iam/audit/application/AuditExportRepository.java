package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditExportOperation;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditExportRepository {

    AuditExportOperation create(
            TenantContext tenant,
            UUID operationId,
            UUID requestedByIdentityId,
            AuditExportOperation.Filter filter,
            Instant occurredFrom,
            Instant occurredUntil,
            Instant snapshotRecordedAt,
            String schemaVersion,
            Instant now);

    Optional<AuditExportOperation> find(TenantContext tenant, UUID operationId);

    AuditExportOperation beginRun(
            TenantContext tenant,
            UUID operationId,
            long expectedRevision,
            Instant now);

    AuditExportOperation checkpoint(
            TenantContext tenant,
            UUID operationId,
            long expectedRevision,
            Instant continuationOccurredAt,
            UUID continuationId,
            long recordCount,
            long byteCount,
            Instant now);

    AuditExportOperation complete(
            TenantContext tenant,
            UUID operationId,
            long expectedRevision,
            String artifactReference,
            long recordCount,
            long byteCount,
            String sha256Hex,
            Instant artifactExpiresAt,
            Instant now);

    AuditExportOperation fail(
            TenantContext tenant,
            UUID operationId,
            long expectedRevision,
            String failureCode,
            Instant now);

    List<AuditRecord> findSourcePage(
            TenantContext tenant,
            AuditExportOperation.Filter filter,
            Instant occurredFrom,
            Instant occurredUntil,
            Instant snapshotRecordedAt,
            Instant afterOccurredAt,
            UUID afterId,
            int limit);
}
