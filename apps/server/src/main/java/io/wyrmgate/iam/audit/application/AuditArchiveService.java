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

public final class AuditArchiveService {

    public static final String HANDLER_TYPE = "audit.archive";
    private static final String RESOURCE_TYPE = "audit-archive-segment";
    private static final String PROCESSING_FAILURE = "audit_archive_processing_failed";
    private static final String VERIFICATION_FAILURE = "audit_archive_verification_failed";
    private static final String INVALID_WORK = "audit_archive_invalid_work";
    private static final int SOURCE_PAGE_SIZE = 500;

    private final AuditArchiveRepository repository;
    private final AuditRetentionPolicyRepository retentionPolicies;
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
            AuditArchiveRepository repository,
            AuditRetentionPolicyRepository retentionPolicies,
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
        this(repository, retentionPolicies, artifactStore, scheduledWork, transactions, ids, objectMapper,
                enabled, claimLease, batchSize, maxAttempts, retryDelay,
                Clock.systemUTC(), "audit-archive-" + UUID.randomUUID());
    }

    AuditArchiveService(
            AuditArchiveRepository repository,
            AuditRetentionPolicyRepository retentionPolicies,
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
        this.repository = Objects.requireNonNull(repository, "repository");
        this.retentionPolicies = Objects.requireNonNull(retentionPolicies, "retentionPolicies");
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
        AuditRetentionPolicyVersion policy = retentionPolicies.findEffective(tenant, now)
                .orElseThrow(() -> new IllegalStateException("audit_retention_policy_unavailable"));
        Instant eligibleThrough = now.minus(policy.archiveEligibilityAge());
        if (occurredUntil.isAfter(eligibleThrough)) {
            throw new IllegalArgumentException("audit_archive_range_not_yet_eligible");
        }

        return transactions.required(() -> {
            UUID segmentId = ids.nextId();
            AuditArchiveSegment segment = repository.create(
                    tenant,
                    segmentId,
                    policy.version(),
                    occurredFrom,
                    occurredUntil,
                    now,
                    AuditArchiveSegment.NDJSON_V1,
                    correlationId,
                    causationId,
                    now);
            scheduledWork.enqueue(
                    tenant,
                    HANDLER_TYPE,
                    segmentId.toString(),
                    new SubjectReference(RESOURCE_TYPE, segmentId, segment.revision()),
                    now,
                    now);
            return segment;
        });
    }

    public AuditArchiveSegment find(TenantContext tenant, UUID segmentId) {
        return repository.find(tenant, segmentId)
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
            switch (executeOne(item)) {
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

        AuditArchiveSegment existing = repository.find(item.tenant(), segmentId).orElse(null);
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

        try {
            AuditArchiveSegment running = transactions.required(() ->
                    repository.beginRun(
                            item.tenant(), segmentId, existing.revision(), clock.instant()));
            AuditArchiveSegment[] current = new AuditArchiveSegment[] { running };
            MessageDigest generatedDigest = sha256();
            long[] generated = new long[] { 0L, 0L };

            String artifactReference = artifactStore.write(
                    item.tenant(),
                    segmentId,
                    output -> {
                        Instant afterOccurredAt = null;
                        UUID afterId = null;
                        while (true) {
                            renew(item);
                            List<AuditRecord> page = repository.findSourcePage(
                                    item.tenant(),
                                    running.occurredFrom(),
                                    running.occurredUntil(),
                                    running.snapshotRecordedAt(),
                                    afterOccurredAt,
                                    afterId,
                                    SOURCE_PAGE_SIZE);
                            if (page.isEmpty()) break;

                            for (AuditRecord record : page) {
                                byte[] line = objectMapper.writeValueAsBytes(ArchiveRecord.from(record));
                                output.write(line);
                                output.write('\n');
                                generatedDigest.update(line);
                                generatedDigest.update((byte) '\n');
                                generated[0]++;
                                generated[1] += line.length + 1L;
                            }

                            AuditRecord last = page.get(page.size() - 1);
                            renew(item);
                            current[0] = transactions.required(() ->
                                    repository.checkpoint(
                                            item.tenant(),
                                            segmentId,
                                            current[0].revision(),
                                            last.occurredAt(),
                                            last.id(),
                                            generated[0],
                                            generated[1],
                                            clock.instant()));
                            afterOccurredAt = last.occurredAt();
                            afterId = last.id();
                            if (page.size() < SOURCE_PAGE_SIZE) break;
                        }
                    });

            String generatedSha = HexFormat.of().formatHex(generatedDigest.digest());
            verify(item.tenant(), artifactReference, generated[0], generated[1], generatedSha);

            Instant completedAt = clock.instant();
            transactions.required(() ->
                    repository.complete(
                            item.tenant(),
                            segmentId,
                            current[0].revision(),
                            artifactReference,
                            generated[0],
                            generated[1],
                            generatedSha,
                            completedAt));
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, completedAt);
            return Outcome.COMPLETED;
        } catch (RuntimeException failure) {
            Instant now = clock.instant();
            String failureCode = failure instanceof ArchiveVerificationException
                    ? VERIFICATION_FAILURE : PROCESSING_FAILURE;
            if (item.work().attemptCount() < maxAttempts) {
                scheduledWork.reschedule(
                        item.tenant(),
                        item.work().id(),
                        leaseOwner,
                        now.plus(retryDelay),
                        failureCode,
                        now);
                return Outcome.RETRYING;
            }

            AuditArchiveSegment current = repository.find(item.tenant(), segmentId).orElse(null);
            if (current != null
                    && current.state() != AuditArchiveSegment.State.SUCCEEDED
                    && current.state() != AuditArchiveSegment.State.FAILED) {
                transactions.required(() ->
                        repository.fail(
                                item.tenant(),
                                segmentId,
                                current.revision(),
                                failureCode,
                                now));
            }
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, failureCode, now);
            return Outcome.FAILED;
        }
    }

    private void verify(
            TenantContext tenant,
            String artifactReference,
            long expectedRecords,
            long expectedBytes,
            String expectedSha) {
        MessageDigest digest = sha256();
        long records = 0L;
        long bytes = 0L;
        byte[] buffer = new byte[8192];
        try (InputStream input = artifactStore.open(tenant, artifactReference)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                bytes += read;
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == (byte) '\n') records++;
                }
            }
        } catch (Exception error) {
            throw new ArchiveVerificationException(error);
        }
        String sha = HexFormat.of().formatHex(digest.digest());
        if (records != expectedRecords || bytes != expectedBytes || !sha.equals(expectedSha)) {
            throw new ArchiveVerificationException("archive artifact verification mismatch");
        }
    }

    private void renew(ClaimedTenantWork item) {
        scheduledWork.renewLease(
                item.tenant(),
                item.work().id(),
                leaseOwner,
                clock.instant(),
                claimLease);
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

    private static final class ArchiveVerificationException extends RuntimeException {
        ArchiveVerificationException(String message) {
            super(message);
        }

        ArchiveVerificationException(Throwable cause) {
            super(cause);
        }
    }

    public record BatchResult(int claimed, int completed, int retrying, int failed) {
        public BatchResult {
            if (claimed < 0 || completed < 0 || retrying < 0 || failed < 0
                    || completed + retrying + failed != claimed) {
                throw new IllegalArgumentException("invalid audit archive batch counts");
            }
        }
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
            UUID causationId) {
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
                    record.causationId());
        }
    }
}
