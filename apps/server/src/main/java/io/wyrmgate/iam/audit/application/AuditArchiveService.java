package io.wyrmgate.iam.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.domain.AuditArchiveSegment;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.audit.domain.AuditRetentionPolicyVersion;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
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

/** Durable ADR-0034 archive generation and immutable retention-policy application service. */
public final class AuditArchiveService {

    public static final String HANDLER_TYPE = "audit.archive";
    private static final int SOURCE_PAGE_SIZE = 500;
    private static final String RESOURCE_TYPE = "audit-archive";
    private static final String FAILURE_CODE = "audit_archive_processing_failed";
    private static final String INVALID_WORK = "audit_archive_invalid_work";

    private final AuditArchiveRepository archives;
    private final AuditEvidenceLifecycleRepository lifecycle;
    private final AuditRetentionPolicyRepository policies;
    private final AuditExportArtifactStore artifactStore;
    private final JdbcScheduledWorkRepository scheduledWork;
    private final TransactionExecutor transactions;
    private final IdGenerator ids;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean enabled;
    private final Duration claimLease;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final String leaseOwner;

    public AuditArchiveService(
            AuditArchiveRepository archives,
            AuditRetentionPolicyRepository policies,
            AuditExportArtifactStore artifactStore,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            ObjectMapper objectMapper,
            boolean enabled,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay) {
        this(
                archives,
                null,
                policies,
                artifactStore,
                scheduledWork,
                transactions,
                ids,
                objectMapper,
                enabled,
                claimLease,
                batchSize,
                maxAttempts,
                retryDelay,
                Clock.systemUTC(),
                "audit-archive-" + UUID.randomUUID());
    }

    public AuditArchiveService(
            AuditArchiveRepository archives,
            AuditEvidenceLifecycleRepository lifecycle,
            AuditRetentionPolicyRepository policies,
            AuditExportArtifactStore artifactStore,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            ObjectMapper objectMapper,
            boolean enabled,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay) {
        this(
                archives,
                lifecycle,
                policies,
                artifactStore,
                scheduledWork,
                transactions,
                ids,
                objectMapper,
                enabled,
                claimLease,
                batchSize,
                maxAttempts,
                retryDelay,
                Clock.systemUTC(),
                "audit-archive-" + UUID.randomUUID());
    }

    AuditArchiveService(
            AuditArchiveRepository archives,
            AuditRetentionPolicyRepository policies,
            AuditExportArtifactStore artifactStore,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            ObjectMapper objectMapper,
            boolean enabled,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay,
            Clock clock,
            String leaseOwner) {
        this(
                archives,
                null,
                policies,
                artifactStore,
                scheduledWork,
                transactions,
                ids,
                objectMapper,
                enabled,
                claimLease,
                batchSize,
                maxAttempts,
                retryDelay,
                clock,
                leaseOwner);
    }

    private AuditArchiveService(
            AuditArchiveRepository archives,
            AuditEvidenceLifecycleRepository lifecycle,
            AuditRetentionPolicyRepository policies,
            AuditExportArtifactStore artifactStore,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            ObjectMapper objectMapper,
            boolean enabled,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay,
            Clock clock,
            String leaseOwner) {
        this.archives = Objects.requireNonNull(archives, "archives");
        this.lifecycle = lifecycle;
        this.policies = Objects.requireNonNull(policies, "policies");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.scheduledWork = Objects.requireNonNull(scheduledWork, "scheduledWork");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.enabled = enabled;
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

    public boolean enabled() {
        return enabled;
    }

    public AuditRetentionPolicyVersion registerPolicy(
            TenantContext tenant,
            long version,
            Duration exportArtifactRetention,
            Duration archiveEligibleAfter,
            Duration minimumOnlineRetention,
            Duration minimumArchiveRetention,
            Instant effectiveFrom) {
        Objects.requireNonNull(tenant, "tenant");
        Instant now = clock.instant();
        AuditRetentionPolicyVersion requested = new AuditRetentionPolicyVersion(
                ids.nextId(),
                version,
                exportArtifactRetention,
                archiveEligibleAfter,
                minimumOnlineRetention,
                minimumArchiveRetention,
                effectiveFrom,
                now);
        AuditRetentionPolicyVersion persisted = transactions.required(() ->
                policies.create(
                        tenant,
                        requested.id(),
                        requested.version(),
                        requested.exportArtifactRetention(),
                        requested.archiveEligibleAfter(),
                        requested.minimumOnlineRetention(),
                        requested.minimumArchiveRetention(),
                        requested.effectiveFrom(),
                        requested.createdAt()));
        if (!samePolicy(requested, persisted)) {
            throw new IllegalStateException("audit_retention_policy_version_conflict");
        }
        return persisted;
    }

    public AuditRetentionPolicyVersion currentPolicy(TenantContext tenant) {
        return policies.findCurrent(tenant, clock.instant())
                .orElseThrow(() -> new IllegalStateException("audit_retention_policy_unconfigured"));
    }

    public AuditArchiveSegment request(
            TenantContext tenant,
            Instant occurredFrom,
            Instant occurredUntil,
            UUID correlationId,
            UUID causationId) {
        if (!enabled) throw new IllegalStateException("audit_archive_unavailable");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(occurredFrom, "occurredFrom");
        Objects.requireNonNull(occurredUntil, "occurredUntil");
        if (!occurredUntil.isAfter(occurredFrom)) {
            throw new IllegalArgumentException("occurredUntil must be after occurredFrom");
        }

        Instant now = clock.instant();
        AuditRetentionPolicyVersion policy = policies.findCurrent(tenant, now)
                .orElseThrow(() -> new IllegalStateException("audit_retention_policy_unconfigured"));
        if (occurredUntil.isAfter(now.minus(policy.archiveEligibleAfter()))) {
            throw new IllegalStateException("audit_archive_not_eligible");
        }

        return transactions.required(() -> {
            AuditArchiveSegment segment = archives.createOrFind(
                    tenant,
                    ids.nextId(),
                    policy.id(),
                    occurredFrom,
                    occurredUntil,
                    now,
                    AuditArchiveSegment.NDJSON_V2,
                    correlationId,
                    causationId,
                    now);
            scheduledWork.enqueue(
                    tenant,
                    HANDLER_TYPE,
                    segment.id().toString(),
                    new SubjectReference(RESOURCE_TYPE, segment.id(), segment.revision()),
                    now,
                    now);
            return segment;
        });
    }

    public AuditArchiveSegment find(TenantContext tenant, UUID segmentId) {
        return archives.find(tenant, segmentId)
                .orElseThrow(() -> new IllegalArgumentException("audit archive segment does not exist"));
    }

    public BatchResult executeAvailable() {
        if (!enabled) return new BatchResult(0, 0, 0, 0);
        List<ClaimedTenantWork> claimed = scheduledWork.claimDueByHandler(
                HANDLER_TYPE, leaseOwner, clock.instant(), claimLease, batchSize);
        int completed = 0;
        int retrying = 0;
        int failed = 0;
        for (ClaimedTenantWork item : claimed) {
            Outcome outcome = executeOne(item);
            switch (outcome) {
                case COMPLETED -> completed++;
                case RETRYING -> retrying++;
                case FAILED -> failed++;
            }
        }
        return new BatchResult(claimed.size(), completed, retrying, failed);
    }

    private Outcome executeOne(ClaimedTenantWork item) {
        UUID segmentId;
        try {
            segmentId = UUID.fromString(item.work().workKey());
        } catch (IllegalArgumentException malformed) {
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, INVALID_WORK, clock.instant());
            return Outcome.FAILED;
        }

        AuditArchiveSegment existing = archives.find(item.tenant(), segmentId).orElse(null);
        if (existing == null) {
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, INVALID_WORK, clock.instant());
            return Outcome.FAILED;
        }
        if (existing.state() == AuditArchiveSegment.State.SUCCEEDED
                || existing.state() == AuditArchiveSegment.State.FAILED) {
            scheduledWork.markCompleted(item.tenant(), item.work().id(), leaseOwner, clock.instant());
            return existing.state() == AuditArchiveSegment.State.SUCCEEDED
                    ? Outcome.COMPLETED : Outcome.FAILED;
        }

        AuditRetentionPolicyVersion policy = policies.findById(
                        item.tenant(), existing.retentionPolicyVersionId())
                .orElse(null);
        if (policy == null) {
            failTerminal(item, existing, "audit_archive_policy_missing");
            return Outcome.FAILED;
        }

        try {
            AuditArchiveSegment running = transactions.required(() ->
                    archives.beginRun(
                            item.tenant(), segmentId, existing.revision(), clock.instant()));
            AuditArchiveSegment[] current = new AuditArchiveSegment[] { running };
            MessageDigest digest = sha256();
            long[] counts = new long[] { 0L, 0L };

            String artifactReference = artifactStore.writeArchive(
                    item.tenant(),
                    segmentId,
                    output -> {
                        Instant afterOccurredAt = null;
                        UUID afterId = null;
                        while (true) {
                            scheduledWork.renewLease(
                                    item.tenant(),
                                    item.work().id(),
                                    leaseOwner,
                                    clock.instant(),
                                    claimLease);
                            List<AuditRecord> page = archives.findSourcePage(
                                    item.tenant(),
                                    running.occurredFrom(),
                                    running.occurredUntil(),
                                    running.snapshotRecordedAt(),
                                    afterOccurredAt,
                                    afterId,
                                    SOURCE_PAGE_SIZE);
                            if (page.isEmpty()) break;

                            for (AuditRecord record : page) {
                                if (!AuditRecordIntegrity.verifies(item.tenant(), record)) {
                                    throw new AuditIntegrityException(record.id());
                                }
                                byte[] line = objectMapper.writeValueAsBytes(ArchiveRecord.from(record));
                                output.write(line);
                                output.write('\n');
                                digest.update(line);
                                digest.update((byte) '\n');
                                counts[0]++;
                                counts[1] += line.length + 1L;
                            }

                            AuditRecord last = page.get(page.size() - 1);
                            scheduledWork.renewLease(
                                    item.tenant(),
                                    item.work().id(),
                                    leaseOwner,
                                    clock.instant(),
                                    claimLease);
                            current[0] = transactions.required(() ->
                                    archives.checkpoint(
                                            item.tenant(),
                                            segmentId,
                                            current[0].revision(),
                                            last.occurredAt(),
                                            last.id(),
                                            counts[0],
                                            counts[1],
                                            clock.instant()));
                            afterOccurredAt = last.occurredAt();
                            afterId = last.id();
                            if (page.size() < SOURCE_PAGE_SIZE) break;
                        }
                    });

            String generatedSha = HexFormat.of().formatHex(digest.digest());
            Verification verification = verifyArtifact(item.tenant(), artifactReference);
            if (verification.recordCount() != counts[0]
                    || verification.byteCount() != counts[1]
                    || !verification.sha256Hex().equals(generatedSha)) {
                throw new IllegalStateException("audit_archive_verification_failed");
            }

            Instant completedAt = clock.instant();
            if (lifecycle != null) {
                indexArchivedRecords(item, running, completedAt);
            }
            Instant minimumRetainUntil = completedAt.plus(policy.minimumArchiveRetention());
            transactions.required(() ->
                    archives.complete(
                            item.tenant(),
                            segmentId,
                            current[0].revision(),
                            artifactReference,
                            counts[0],
                            counts[1],
                            generatedSha,
                            completedAt,
                            minimumRetainUntil,
                            completedAt));
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, completedAt);
            return Outcome.COMPLETED;
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

            AuditArchiveSegment current = archives.find(item.tenant(), segmentId).orElse(null);
            if (current != null
                    && current.state() != AuditArchiveSegment.State.SUCCEEDED
                    && current.state() != AuditArchiveSegment.State.FAILED) {
                transactions.required(() ->
                        archives.fail(
                                item.tenant(),
                                segmentId,
                                current.revision(),
                                FAILURE_CODE,
                                now));
            }
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, FAILURE_CODE, now);
            return Outcome.FAILED;
        }
    }

    private void indexArchivedRecords(
            ClaimedTenantWork item,
            AuditArchiveSegment segment,
            Instant archivedAt) {
        Instant afterOccurredAt = null;
        UUID afterId = null;
        while (true) {
            scheduledWork.renewLease(
                    item.tenant(),
                    item.work().id(),
                    leaseOwner,
                    clock.instant(),
                    claimLease);
            List<AuditRecord> page = archives.findSourcePage(
                    item.tenant(),
                    segment.occurredFrom(),
                    segment.occurredUntil(),
                    segment.snapshotRecordedAt(),
                    afterOccurredAt,
                    afterId,
                    SOURCE_PAGE_SIZE);
            if (page.isEmpty()) return;
            transactions.required(() -> {
                lifecycle.indexArchivedRecords(item.tenant(), segment, page, archivedAt);
                return null;
            });
            AuditRecord last = page.get(page.size() - 1);
            afterOccurredAt = last.occurredAt();
            afterId = last.id();
            if (page.size() < SOURCE_PAGE_SIZE) return;
        }
    }

    private void failTerminal(
            ClaimedTenantWork item,
            AuditArchiveSegment segment,
            String code) {
        Instant now = clock.instant();
        transactions.required(() ->
                archives.fail(
                        item.tenant(),
                        segment.id(),
                        segment.revision(),
                        code,
                        now));
        scheduledWork.markCompleted(
                item.tenant(), item.work().id(), leaseOwner, code, now);
    }

    private Verification verifyArtifact(
            TenantContext tenant,
            String artifactReference) {
        MessageDigest digest = sha256();
        long bytes = 0;
        long records = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = artifactStore.open(tenant, artifactReference)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                bytes += read;
                for (int index = 0; index < read; index++) {
                    if (buffer[index] == (byte) '\n') records++;
                }
            }
        } catch (Exception failure) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("audit archive verification failed", failure);
        }
        return new Verification(records, bytes, HexFormat.of().formatHex(digest.digest()));
    }

    private static boolean samePolicy(
            AuditRetentionPolicyVersion requested,
            AuditRetentionPolicyVersion persisted) {
        return requested.version() == persisted.version()
                && requested.exportArtifactRetention().equals(persisted.exportArtifactRetention())
                && requested.archiveEligibleAfter().equals(persisted.archiveEligibleAfter())
                && requested.minimumOnlineRetention().equals(persisted.minimumOnlineRetention())
                && requested.minimumArchiveRetention().equals(persisted.minimumArchiveRetention())
                && requested.effectiveFrom().equals(persisted.effectiveFrom());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private enum Outcome {
        COMPLETED,
        RETRYING,
        FAILED
    }

    public record BatchResult(int claimed, int completed, int retrying, int failed) {
        public BatchResult {
            if (claimed < 0 || completed < 0 || retrying < 0 || failed < 0
                    || completed + retrying + failed != claimed) {
                throw new IllegalArgumentException("invalid audit archive batch counts");
            }
        }
    }

    private record Verification(
            long recordCount,
            long byteCount,
            String sha256Hex) {
    }

    private record ArchiveRecord(
            UUID id,
            Instant occurredAt,
            Instant recordedAt,
            UUID actorId,
            String actionType,
            String resourceType,
            UUID resourceId,
            String outcome,
            UUID correlationId,
            UUID causationId,
            io.wyrmgate.iam.audit.domain.AuditMaterialSnapshot materialSnapshot,
            io.wyrmgate.iam.audit.domain.AuditIntegrityMetadata integrityMetadata) {
        static ArchiveRecord from(AuditRecord record) {
            return new ArchiveRecord(
                    record.id(),
                    record.occurredAt(),
                    record.recordedAt(),
                    record.actorId(),
                    record.actionType(),
                    record.resourceType(),
                    record.resourceId(),
                    record.outcome().name(),
                    record.correlationId(),
                    record.causationId(),
                    record.materialSnapshot(),
                    record.integrityMetadata());
        }
    }
}
