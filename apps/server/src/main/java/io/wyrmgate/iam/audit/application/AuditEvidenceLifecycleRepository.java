package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditArchiveSegment;
import io.wyrmgate.iam.audit.domain.AuditLegalHold;
import io.wyrmgate.iam.audit.domain.AuditPurgeOperation;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.audit.domain.AuditSelection;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEvidenceLifecycleRepository {

    void lockLifecycle(TenantContext tenant);

    AuditLegalHold createHold(
            TenantContext tenant,
            UUID holdId,
            AuditSelection selection,
            String reasonCode,
            String caseReference,
            UUID createdByIdentityId,
            UUID correlationId,
            UUID causationId,
            Instant now);

    Optional<AuditLegalHold> findHold(TenantContext tenant, UUID holdId);

    AuditLegalHold releaseHold(
            TenantContext tenant,
            UUID holdId,
            long expectedRevision,
            UUID releasedByIdentityId,
            Instant now);

    boolean hasMatchingActiveHold(
            TenantContext tenant,
            AuditSelection selection,
            Instant snapshotRecordedAt);

    AuditPurgeOperation createPurge(
            TenantContext tenant,
            UUID purgeId,
            UUID retentionPolicyVersionId,
            UUID archiveSegmentId,
            UUID requestedByIdentityId,
            AuditSelection selection,
            Instant snapshotRecordedAt,
            String reasonCode,
            UUID correlationId,
            UUID causationId,
            Instant now);

    Optional<AuditPurgeOperation> findPurge(TenantContext tenant, UUID purgeId);

    AuditPurgeOperation approvePurge(
            TenantContext tenant,
            UUID purgeId,
            long expectedRevision,
            UUID approvedByIdentityId,
            Instant now);

    AuditPurgeOperation blockApprovedPurge(
            TenantContext tenant,
            UUID purgeId,
            long expectedRevision,
            String failureCode,
            Instant now);

    AuditPurgeOperation beginPurge(
            TenantContext tenant,
            UUID purgeId,
            long expectedRevision,
            Instant now);

    AuditPurgeOperation finishPurge(
            TenantContext tenant,
            UUID purgeId,
            long expectedRevision,
            AuditPurgeOperation.State terminalState,
            long deletedRecordCount,
            String failureCode,
            Instant now);

    long executeFencedDelete(
            TenantContext tenant,
            AuditPurgeOperation operation);

    void indexArchivedRecords(
            TenantContext tenant,
            AuditArchiveSegment segment,
            List<AuditRecord> records,
            Instant archivedAt);
}
