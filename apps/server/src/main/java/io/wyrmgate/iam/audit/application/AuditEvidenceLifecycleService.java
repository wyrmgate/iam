package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditArchiveSegment;
import io.wyrmgate.iam.audit.domain.AuditLegalHold;
import io.wyrmgate.iam.audit.domain.AuditPurgeOperation;
import io.wyrmgate.iam.audit.domain.AuditRetentionPolicyVersion;
import io.wyrmgate.iam.audit.domain.AuditSelection;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.ClaimedTenantWork;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.SubjectReference;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** ADR-0035 legal-hold and destructive Audit evidence lifecycle service. */
public final class AuditEvidenceLifecycleService {

    public static final String PURGE_HANDLER_TYPE = "audit.purge";
    private static final String PURGE_RESOURCE_TYPE = "audit-purge";
    private static final String INVALID_WORK = "audit_purge_invalid_work";
    private static final String FAILURE_CODE = "audit_purge_processing_failed";

    private final AuditEvidenceLifecycleRepository lifecycle;
    private final AuditArchiveRepository archives;
    private final AuditRetentionPolicyRepository policies;
    private static final String HOLD_IDEMPOTENCY = "api.audit.legal-hold.create.v1";
    private static final String PURGE_IDEMPOTENCY = "api.audit.purge.create.v1";

    private final AuditExportArtifactStore artifactStore;
    private final JdbcIdempotencyRepository idempotency;
    private final JdbcScheduledWorkRepository scheduledWork;
    private final TransactionExecutor transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final boolean purgeEnabled;
    private final Duration claimLease;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final String leaseOwner;

    public AuditEvidenceLifecycleService(
            AuditEvidenceLifecycleRepository lifecycle,
            AuditArchiveRepository archives,
            AuditRetentionPolicyRepository policies,
            AuditExportArtifactStore artifactStore,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            boolean purgeEnabled,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay) {
        this(
                lifecycle,
                archives,
                policies,
                artifactStore,
                null,
                scheduledWork,
                transactions,
                ids,
                purgeEnabled,
                claimLease,
                batchSize,
                maxAttempts,
                retryDelay,
                Clock.systemUTC(),
                "audit-purge-" + UUID.randomUUID());
    }

    public AuditEvidenceLifecycleService(
            AuditEvidenceLifecycleRepository lifecycle,
            AuditArchiveRepository archives,
            AuditRetentionPolicyRepository policies,
            AuditExportArtifactStore artifactStore,
            JdbcIdempotencyRepository idempotency,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            boolean purgeEnabled,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay) {
        this(
                lifecycle,
                archives,
                policies,
                artifactStore,
                idempotency,
                scheduledWork,
                transactions,
                ids,
                purgeEnabled,
                claimLease,
                batchSize,
                maxAttempts,
                retryDelay,
                Clock.systemUTC(),
                "audit-purge-" + UUID.randomUUID());
    }

    AuditEvidenceLifecycleService(
            AuditEvidenceLifecycleRepository lifecycle,
            AuditArchiveRepository archives,
            AuditRetentionPolicyRepository policies,
            AuditExportArtifactStore artifactStore,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            boolean purgeEnabled,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay,
            Clock clock,
            String leaseOwner) {
        this(
                lifecycle,
                archives,
                policies,
                artifactStore,
                null,
                scheduledWork,
                transactions,
                ids,
                purgeEnabled,
                claimLease,
                batchSize,
                maxAttempts,
                retryDelay,
                clock,
                leaseOwner);
    }

    private AuditEvidenceLifecycleService(
            AuditEvidenceLifecycleRepository lifecycle,
            AuditArchiveRepository archives,
            AuditRetentionPolicyRepository policies,
            AuditExportArtifactStore artifactStore,
            JdbcIdempotencyRepository idempotency,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            boolean purgeEnabled,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay,
            Clock clock,
            String leaseOwner) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.archives = Objects.requireNonNull(archives, "archives");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.idempotency = idempotency;
        this.scheduledWork = Objects.requireNonNull(scheduledWork, "scheduledWork");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.purgeEnabled = purgeEnabled;
        this.claimLease = positive(claimLease, "claimLease");
        this.retryDelay = positive(retryDelay, "retryDelay");
        if (batchSize < 1 || batchSize > 200) {
            throw new IllegalArgumentException("batchSize must be between 1 and 200");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 100");
        }
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseOwner = leaseOwner;
    }

    public AuditLegalHold createHold(
            TenantContext tenant,
            AuditSelection selection,
            String reasonCode,
            String caseReference,
            UUID createdByIdentityId,
            UUID correlationId,
            UUID causationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(createdByIdentityId, "createdByIdentityId");
        Instant now = clock.instant();
        return transactions.required(() ->
                lifecycle.createHold(
                        tenant,
                        ids.nextId(),
                        selection,
                        reasonCode,
                        caseReference,
                        createdByIdentityId,
                        correlationId,
                        causationId,
                        now));
    }

    public AuditLegalHold createHold(
            TenantContext tenant,
            AuditSelection selection,
            String reasonCode,
            String caseReference,
            UUID createdByIdentityId,
            UUID correlationId,
            UUID causationId,
            String idempotencyKey,
            RequestFingerprint fingerprint) {
        if (idempotency == null) {
            return createHold(
                    tenant, selection, reasonCode, caseReference,
                    createdByIdentityId, correlationId, causationId);
        }
        Instant now = clock.instant();
        return transactions.required(() -> {
            var registration = idempotency.register(
                    tenant, HOLD_IDEMPOTENCY, idempotencyKey, fingerprint, now, null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                if (!"COMPLETED".equals(registration.operationState())
                        || !"audit-legal-hold".equals(registration.resourceType())
                        || registration.resourceId() == null) {
                    throw new IllegalStateException("audit_legal_hold_idempotency_in_progress");
                }
                return lifecycle.findHold(tenant, registration.resourceId())
                        .orElseThrow(() -> new IllegalStateException("completed audit legal hold is missing"));
            }
            AuditLegalHold hold = lifecycle.createHold(
                    tenant,
                    ids.nextId(),
                    selection,
                    reasonCode,
                    caseReference,
                    createdByIdentityId,
                    correlationId,
                    causationId,
                    now);
            idempotency.complete(
                    tenant, HOLD_IDEMPOTENCY, idempotencyKey, fingerprint,
                    "audit-legal-hold", hold.id(), now);
            return hold;
        });
    }

    public AuditLegalHold findHold(TenantContext tenant, UUID holdId) {
        return lifecycle.findHold(tenant, holdId)
                .orElseThrow(() -> new IllegalArgumentException("audit legal hold does not exist"));
    }

    public AuditLegalHold releaseHold(
            TenantContext tenant,
            UUID holdId,
            long expectedRevision,
            UUID releasedByIdentityId) {
        Objects.requireNonNull(releasedByIdentityId, "releasedByIdentityId");
        return transactions.required(() -> {
            lifecycle.lockLifecycle(tenant);
            return lifecycle.releaseHold(
                    tenant,
                    holdId,
                    expectedRevision,
                    releasedByIdentityId,
                    clock.instant());
        });
    }

    public AuditPurgeOperation requestPurge(
            TenantContext tenant,
            UUID requestedByIdentityId,
            UUID archiveSegmentId,
            AuditSelection selection,
            String reasonCode,
            UUID correlationId,
            UUID causationId) {
        if (!purgeEnabled) throw new IllegalStateException("audit_purge_unavailable");
        Objects.requireNonNull(requestedByIdentityId, "requestedByIdentityId");
        Objects.requireNonNull(archiveSegmentId, "archiveSegmentId");
        Objects.requireNonNull(selection, "selection");

        Instant now = clock.instant();
        AuditRetentionPolicyVersion policy = policies.findCurrent(tenant, now)
                .orElseThrow(() -> new IllegalStateException("audit_retention_policy_unconfigured"));
        AuditArchiveSegment archive = archives.find(tenant, archiveSegmentId)
                .orElseThrow(() -> new IllegalArgumentException("audit archive segment does not exist"));
        requireArchiveCoverage(archive, selection, archive.snapshotRecordedAt());
        if (selection.occurredUntil().isAfter(now.minus(policy.minimumOnlineRetention()))) {
            throw new IllegalStateException("audit_purge_minimum_online_retention_not_elapsed");
        }

        return transactions.required(() -> {
            lifecycle.lockLifecycle(tenant);
            if (lifecycle.hasMatchingActiveHold(tenant, selection, archive.snapshotRecordedAt())) {
                throw new IllegalStateException("audit_purge_blocked_by_legal_hold");
            }
            return lifecycle.createPurge(
                    tenant,
                    ids.nextId(),
                    policy.id(),
                    archive.id(),
                    requestedByIdentityId,
                    selection,
                    archive.snapshotRecordedAt(),
                    reasonCode,
                    correlationId,
                    causationId,
                    now);
        });
    }

    public AuditPurgeOperation requestPurge(
            TenantContext tenant,
            UUID requestedByIdentityId,
            UUID archiveSegmentId,
            AuditSelection selection,
            String reasonCode,
            UUID correlationId,
            UUID causationId,
            String idempotencyKey,
            RequestFingerprint fingerprint) {
        if (idempotency == null) {
            return requestPurge(
                    tenant, requestedByIdentityId, archiveSegmentId, selection,
                    reasonCode, correlationId, causationId);
        }
        if (!purgeEnabled) throw new IllegalStateException("audit_purge_unavailable");
        Objects.requireNonNull(requestedByIdentityId, "requestedByIdentityId");
        Objects.requireNonNull(archiveSegmentId, "archiveSegmentId");
        Objects.requireNonNull(selection, "selection");
        Instant now = clock.instant();
        AuditRetentionPolicyVersion policy = policies.findCurrent(tenant, now)
                .orElseThrow(() -> new IllegalStateException("audit_retention_policy_unconfigured"));
        AuditArchiveSegment archive = archives.find(tenant, archiveSegmentId)
                .orElseThrow(() -> new IllegalArgumentException("audit archive segment does not exist"));
        requireArchiveCoverage(archive, selection, archive.snapshotRecordedAt());
        if (selection.occurredUntil().isAfter(now.minus(policy.minimumOnlineRetention()))) {
            throw new IllegalStateException("audit_purge_minimum_online_retention_not_elapsed");
        }
        return transactions.required(() -> {
            var registration = idempotency.register(
                    tenant, PURGE_IDEMPOTENCY, idempotencyKey, fingerprint, now, null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                if (!"COMPLETED".equals(registration.operationState())
                        || !"audit-purge".equals(registration.resourceType())
                        || registration.resourceId() == null) {
                    throw new IllegalStateException("audit_purge_idempotency_in_progress");
                }
                return lifecycle.findPurge(tenant, registration.resourceId())
                        .orElseThrow(() -> new IllegalStateException("completed audit purge is missing"));
            }
            lifecycle.lockLifecycle(tenant);
            if (lifecycle.hasMatchingActiveHold(tenant, selection, archive.snapshotRecordedAt())) {
                throw new IllegalStateException("audit_purge_blocked_by_legal_hold");
            }
            AuditPurgeOperation purge = lifecycle.createPurge(
                    tenant, ids.nextId(), policy.id(), archive.id(), requestedByIdentityId,
                    selection, archive.snapshotRecordedAt(), reasonCode,
                    correlationId, causationId, now);
            idempotency.complete(
                    tenant, PURGE_IDEMPOTENCY, idempotencyKey, fingerprint,
                    "audit-purge", purge.id(), now);
            return purge;
        });
    }

    public AuditPurgeOperation findPurge(TenantContext tenant, UUID purgeId) {
        return lifecycle.findPurge(tenant, purgeId)
                .orElseThrow(() -> new IllegalArgumentException("audit purge operation does not exist"));
    }

    public AuditPurgeOperation approvePurge(
            TenantContext tenant,
            UUID purgeId,
            long expectedRevision,
            UUID approvedByIdentityId) {
        Objects.requireNonNull(approvedByIdentityId, "approvedByIdentityId");
        Instant now = clock.instant();
        return transactions.required(() -> {
            lifecycle.lockLifecycle(tenant);
            AuditPurgeOperation current = findPurge(tenant, purgeId);
            if (current.requestedByIdentityId().equals(approvedByIdentityId)) {
                throw new IllegalArgumentException("purge requester and approver must differ");
            }
            validatePrerequisites(tenant, current, now);
            AuditPurgeOperation approved = lifecycle.approvePurge(
                    tenant,
                    purgeId,
                    expectedRevision,
                    approvedByIdentityId,
                    now);
            scheduledWork.enqueue(
                    tenant,
                    PURGE_HANDLER_TYPE,
                    purgeId.toString(),
                    new SubjectReference(PURGE_RESOURCE_TYPE, purgeId, approved.revision()),
                    now,
                    now);
            return approved;
        });
    }

    public BatchResult executeAvailable() {
        if (!purgeEnabled) return new BatchResult(0, 0, 0, 0);
        List<ClaimedTenantWork> claimed = scheduledWork.claimDueByHandler(
                PURGE_HANDLER_TYPE,
                leaseOwner,
                clock.instant(),
                claimLease,
                batchSize);
        int completed = 0;
        int retrying = 0;
        int blocked = 0;
        int failed = 0;
        for (ClaimedTenantWork item : claimed) {
            Outcome outcome = executeOne(item);
            switch (outcome) {
                case COMPLETED -> completed++;
                case RETRYING -> retrying++;
                case BLOCKED -> blocked++;
                case FAILED -> failed++;
            }
        }
        return new BatchResult(claimed.size(), completed, retrying, blocked, failed);
    }

    private Outcome executeOne(ClaimedTenantWork item) {
        UUID purgeId;
        try {
            purgeId = UUID.fromString(item.work().workKey());
        } catch (IllegalArgumentException malformed) {
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, INVALID_WORK, clock.instant());
            return Outcome.FAILED;
        }

        AuditPurgeOperation current = lifecycle.findPurge(item.tenant(), purgeId).orElse(null);
        if (current == null) {
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, INVALID_WORK, clock.instant());
            return Outcome.FAILED;
        }
        if (terminal(current.state())) {
            scheduledWork.markCompleted(item.tenant(), item.work().id(), leaseOwner, clock.instant());
            return current.state() == AuditPurgeOperation.State.SUCCEEDED
                    ? Outcome.COMPLETED
                    : current.state() == AuditPurgeOperation.State.BLOCKED
                            ? Outcome.BLOCKED : Outcome.FAILED;
        }
        if (current.state() != AuditPurgeOperation.State.APPROVED) {
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, INVALID_WORK, clock.instant());
            return Outcome.FAILED;
        }

        try {
            validatePrerequisites(item.tenant(), current, clock.instant());
            AuditArchiveSegment archive = archives.find(item.tenant(), current.archiveSegmentId())
                    .orElseThrow(() -> new Blocked("audit_purge_archive_missing"));
            verifyArtifact(item.tenant(), archive);

            Instant now = clock.instant();
            transactions.required(() -> {
                lifecycle.lockLifecycle(item.tenant());
                AuditPurgeOperation latest = lifecycle.findPurge(item.tenant(), purgeId)
                        .orElseThrow(() -> new Blocked("audit_purge_missing"));
                validatePrerequisites(item.tenant(), latest, now);
                AuditPurgeOperation running = lifecycle.beginPurge(
                        item.tenant(), purgeId, latest.revision(), now);
                long deleted = lifecycle.executeFencedDelete(item.tenant(), running);
                lifecycle.finishPurge(
                        item.tenant(),
                        purgeId,
                        running.revision(),
                        AuditPurgeOperation.State.SUCCEEDED,
                        deleted,
                        null,
                        now);
                return null;
            });
            scheduledWork.markCompleted(item.tenant(), item.work().id(), leaseOwner, now);
            return Outcome.COMPLETED;
        } catch (Blocked blocked) {
            Instant now = clock.instant();
            AuditPurgeOperation latest = lifecycle.findPurge(item.tenant(), purgeId).orElse(null);
            if (latest != null && latest.state() == AuditPurgeOperation.State.APPROVED) {
                transactions.required(() -> {
                    lifecycle.lockLifecycle(item.tenant());
                    lifecycle.blockApprovedPurge(
                            item.tenant(), purgeId, latest.revision(), blocked.code, now);
                    return null;
                });
            }
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, blocked.code, now);
            return Outcome.BLOCKED;
        } catch (RuntimeException failure) {
            Instant now = clock.instant();
            if (item.work().attemptCount() < maxAttempts) {
                scheduledWork.reschedule(
                        item.tenant(),
                        item.work().id(),
                        leaseOwner,
                        now.plus(retryDelay),
                        FAILURE_CODE,
                        now);
                return Outcome.RETRYING;
            }
            AuditPurgeOperation latest = lifecycle.findPurge(item.tenant(), purgeId).orElse(null);
            if (latest != null && latest.state() == AuditPurgeOperation.State.APPROVED) {
                transactions.required(() -> {
                    AuditPurgeOperation running = lifecycle.beginPurge(
                            item.tenant(), purgeId, latest.revision(), now);
                    lifecycle.finishPurge(
                            item.tenant(),
                            purgeId,
                            running.revision(),
                            AuditPurgeOperation.State.FAILED,
                            0,
                            FAILURE_CODE,
                            now);
                    return null;
                });
            }
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, FAILURE_CODE, now);
            return Outcome.FAILED;
        }
    }

    private void validatePrerequisites(
            TenantContext tenant,
            AuditPurgeOperation purge,
            Instant now) {
        if (purge.approvedByIdentityId() != null
                && purge.approvedByIdentityId().equals(purge.requestedByIdentityId())) {
            throw new Blocked("audit_purge_dual_control_invalid");
        }
        AuditRetentionPolicyVersion policy = policies.findById(
                        tenant, purge.retentionPolicyVersionId())
                .orElseThrow(() -> new Blocked("audit_purge_policy_missing"));
        if (purge.selection().occurredUntil().isAfter(now.minus(policy.minimumOnlineRetention()))) {
            throw new Blocked("audit_purge_minimum_online_retention_not_elapsed");
        }
        AuditArchiveSegment archive = archives.find(tenant, purge.archiveSegmentId())
                .orElseThrow(() -> new Blocked("audit_purge_archive_missing"));
        requireArchiveCoverage(archive, purge.selection(), purge.snapshotRecordedAt());
        if (lifecycle.hasMatchingActiveHold(
                tenant, purge.selection(), purge.snapshotRecordedAt())) {
            throw new Blocked("audit_purge_blocked_by_legal_hold");
        }
    }

    private static void requireArchiveCoverage(
            AuditArchiveSegment archive,
            AuditSelection selection,
            Instant requiredSnapshotCutoff) {
        if (archive.state() != AuditArchiveSegment.State.SUCCEEDED
                || archive.verifiedAt() == null
                || archive.artifactReference() == null
                || archive.sha256Hex() == null
                || archive.occurredFrom().isAfter(selection.occurredFrom())
                || archive.occurredUntil().isBefore(selection.occurredUntil())
                || archive.snapshotRecordedAt().isBefore(requiredSnapshotCutoff)) {
            throw new Blocked("audit_purge_archive_not_verified_or_covering");
        }
    }

    private void verifyArtifact(
            TenantContext tenant,
            AuditArchiveSegment archive) {
        MessageDigest digest = sha256();
        long bytes = 0;
        long records = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = artifactStore.open(tenant, archive.artifactReference())) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                bytes += read;
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == (byte) '\n') records++;
                }
            }
        } catch (Exception error) {
            throw new Blocked("audit_purge_archive_unavailable");
        }
        String sha = HexFormat.of().formatHex(digest.digest());
        if (bytes != archive.byteCount()
                || records != archive.recordCount()
                || !sha.equals(archive.sha256Hex())) {
            throw new Blocked("audit_purge_archive_verification_failed");
        }
    }

    private static boolean terminal(AuditPurgeOperation.State state) {
        return state == AuditPurgeOperation.State.SUCCEEDED
                || state == AuditPurgeOperation.State.FAILED
                || state == AuditPurgeOperation.State.BLOCKED;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private enum Outcome { COMPLETED, RETRYING, BLOCKED, FAILED }

    private static final class Blocked extends RuntimeException {
        private final String code;
        private Blocked(String code) {
            super(code);
            this.code = code;
        }
    }

    public record BatchResult(
            int claimed,
            int completed,
            int retrying,
            int blocked,
            int failed) {
        public BatchResult {
            if (claimed < 0 || completed < 0 || retrying < 0 || blocked < 0 || failed < 0
                    || completed + retrying + blocked + failed != claimed) {
                throw new IllegalArgumentException("invalid audit purge batch counts");
            }
        }
    }
}
