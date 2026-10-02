package io.wyrmgate.iam.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wyrmgate.iam.audit.application.AuditQueryModels.AuditFilter;
import io.wyrmgate.iam.audit.domain.AuditExportOperation;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.id.IdGenerator;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository;
import io.wyrmgate.iam.platform.persistence.JdbcIdempotencyRepository.RegistrationKind;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.ClaimedTenantWork;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.SubjectReference;
import io.wyrmgate.iam.platform.persistence.RequestFingerprint;
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

/** Durable ADR-0034 export application/process service. */
public final class AuditExportService {

    public static final String HANDLER_TYPE = "audit.export";
    private static final String IDEMPOTENCY_NAMESPACE = "api.audit.export.create.v1";
    private static final int SOURCE_PAGE_SIZE = 500;
    private static final String RESOURCE_TYPE = "audit-export";
    private static final String FAILURE_CODE = "audit_export_processing_failed";
    private static final String INVALID_WORK = "audit_export_invalid_work";

    private final AuditExportRepository repository;
    private final AuditExportArtifactStore artifactStore;
    private final JdbcIdempotencyRepository idempotency;
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
    private final Duration artifactRetention;
    private final String leaseOwner;

    public AuditExportService(
            AuditExportRepository repository,
            AuditExportArtifactStore artifactStore,
            JdbcIdempotencyRepository idempotency,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            ObjectMapper objectMapper,
            boolean enabled,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay,
            Duration artifactRetention) {
        this(repository, artifactStore, idempotency, scheduledWork, transactions, ids, objectMapper,
                enabled, claimLease, batchSize, maxAttempts, retryDelay, artifactRetention,
                Clock.systemUTC(), "audit-export-" + UUID.randomUUID());
    }

    AuditExportService(
            AuditExportRepository repository,
            AuditExportArtifactStore artifactStore,
            JdbcIdempotencyRepository idempotency,
            JdbcScheduledWorkRepository scheduledWork,
            TransactionExecutor transactions,
            IdGenerator ids,
            ObjectMapper objectMapper,
            boolean enabled,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay,
            Duration artifactRetention,
            Clock clock,
            String leaseOwner) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.scheduledWork = Objects.requireNonNull(scheduledWork, "scheduledWork");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.enabled = enabled;
        this.claimLease = positive(claimLease, "claimLease");
        this.retryDelay = positive(retryDelay, "retryDelay");
        if (batchSize < 1 || batchSize > 200) throw new IllegalArgumentException("batchSize must be between 1 and 200");
        if (maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("maxAttempts must be between 1 and 100");
        if (artifactRetention != null && (artifactRetention.isZero() || artifactRetention.isNegative())) {
            throw new IllegalArgumentException("artifactRetention must be positive when configured");
        }
        if (leaseOwner == null || leaseOwner.isBlank()) throw new IllegalArgumentException("leaseOwner must not be blank");
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.artifactRetention = artifactRetention;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseOwner = leaseOwner;
    }

    public boolean enabled() {
        return enabled;
    }

    public AuditExportOperation request(
            TenantContext tenant,
            UUID requestedByIdentityId,
            AuditFilter filter,
            Instant occurredFrom,
            Instant occurredUntil,
            String idempotencyKey,
            RequestFingerprint fingerprint) {
        if (!enabled) throw new IllegalStateException("audit_export_unavailable");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(requestedByIdentityId, "requestedByIdentityId");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(occurredFrom, "occurredFrom");
        Objects.requireNonNull(occurredUntil, "occurredUntil");
        if (!occurredUntil.isAfter(occurredFrom)) {
            throw new IllegalArgumentException("occurredUntil must be after occurredFrom");
        }
        Instant now = clock.instant();
        return transactions.required(() -> {
            var registration = idempotency.register(
                    tenant, IDEMPOTENCY_NAMESPACE, idempotencyKey, fingerprint, now, null);
            if (registration.kind() == RegistrationKind.REPLAY) {
                if (!"COMPLETED".equals(registration.operationState())
                        || !RESOURCE_TYPE.equals(registration.resourceType())
                        || registration.resourceId() == null) {
                    throw new IllegalStateException("audit_export_idempotency_in_progress");
                }
                return repository.find(tenant, registration.resourceId())
                        .orElseThrow(() -> new IllegalStateException("completed audit export is missing"));
            }

            UUID operationId = ids.nextId();
            AuditExportOperation.Filter exportFilter = new AuditExportOperation.Filter(
                    filter.actorId(),
                    filter.actionType(),
                    filter.resourceType(),
                    filter.resourceId(),
                    filter.outcome(),
                    filter.correlationId());
            AuditExportOperation operation = repository.create(
                    tenant,
                    operationId,
                    requestedByIdentityId,
                    exportFilter,
                    occurredFrom,
                    occurredUntil,
                    now,
                    AuditExportOperation.NDJSON_V1,
                    now);
            scheduledWork.enqueue(
                    tenant,
                    HANDLER_TYPE,
                    operationId.toString(),
                    new SubjectReference(RESOURCE_TYPE, operationId, operation.revision()),
                    now,
                    now);
            idempotency.complete(
                    tenant,
                    IDEMPOTENCY_NAMESPACE,
                    idempotencyKey,
                    fingerprint,
                    RESOURCE_TYPE,
                    operationId,
                    now);
            return operation;
        });
    }

    public AuditExportOperation find(TenantContext tenant, UUID operationId) {
        return repository.find(tenant, operationId)
                .orElseThrow(() -> new IllegalArgumentException("audit export does not exist"));
    }

    public Download openDownload(TenantContext tenant, UUID operationId) {
        AuditExportOperation operation = find(tenant, operationId);
        Instant now = clock.instant();
        if (operation.state() != AuditExportOperation.State.SUCCEEDED) {
            throw new IllegalStateException("audit_export_not_ready");
        }
        if (operation.artifactExpiredAt(now)) {
            throw new IllegalStateException("audit_export_artifact_expired");
        }
        return new Download(
                operation,
                artifactStore.open(tenant, operation.artifactReference()),
                "application/x-ndjson",
                "audit-export-" + operation.id() + ".ndjson");
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
        UUID exportId;
        try {
            exportId = UUID.fromString(item.work().workKey());
        } catch (IllegalArgumentException malformed) {
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, INVALID_WORK, clock.instant());
            return Outcome.FAILED;
        }

        AuditExportOperation existing = repository.find(item.tenant(), exportId).orElse(null);
        if (existing == null) {
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, INVALID_WORK, clock.instant());
            return Outcome.FAILED;
        }
        if (existing.state() == AuditExportOperation.State.SUCCEEDED
                || existing.state() == AuditExportOperation.State.FAILED) {
            scheduledWork.markCompleted(item.tenant(), item.work().id(), leaseOwner, clock.instant());
            return existing.state() == AuditExportOperation.State.SUCCEEDED
                    ? Outcome.COMPLETED : Outcome.FAILED;
        }

        try {
            AuditExportOperation running = transactions.required(() ->
                    repository.beginRun(
                            item.tenant(), exportId, existing.revision(), clock.instant()));
            AuditExportOperation[] current = new AuditExportOperation[] { running };
            MessageDigest digest = sha256();
            long[] counts = new long[] { 0L, 0L };

            String artifactReference = artifactStore.write(
                    item.tenant(),
                    exportId,
                    output -> {
                        Instant afterOccurredAt = null;
                        UUID afterId = null;
                        while (true) {
                            List<AuditRecord> page = repository.findSourcePage(
                                    item.tenant(),
                                    running.filter(),
                                    running.occurredFrom(),
                                    running.occurredUntil(),
                                    running.snapshotRecordedAt(),
                                    afterOccurredAt,
                                    afterId,
                                    SOURCE_PAGE_SIZE);
                            if (page.isEmpty()) break;

                            for (AuditRecord record : page) {
                                byte[] line = objectMapper.writeValueAsBytes(ExportRecord.from(record));
                                output.write(line);
                                output.write('\n');
                                digest.update(line);
                                digest.update((byte) '\n');
                                counts[0]++;
                                counts[1] += line.length + 1L;
                            }

                            AuditRecord last = page.get(page.size() - 1);
                            current[0] = transactions.required(() ->
                                    repository.checkpoint(
                                            item.tenant(),
                                            exportId,
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

            Instant completedAt = clock.instant();
            Instant expiresAt = artifactRetention == null
                    ? null : completedAt.plus(artifactRetention);
            String sha = HexFormat.of().formatHex(digest.digest());
            transactions.required(() ->
                    repository.complete(
                            item.tenant(),
                            exportId,
                            current[0].revision(),
                            artifactReference,
                            counts[0],
                            counts[1],
                            sha,
                            expiresAt,
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

            AuditExportOperation current = repository.find(item.tenant(), exportId).orElse(null);
            if (current != null
                    && current.state() != AuditExportOperation.State.SUCCEEDED
                    && current.state() != AuditExportOperation.State.FAILED) {
                transactions.required(() ->
                        repository.fail(
                                item.tenant(),
                                exportId,
                                current.revision(),
                                FAILURE_CODE,
                                now));
            }
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, FAILURE_CODE, now);
            return Outcome.FAILED;
        }
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

    public record Download(
            AuditExportOperation operation,
            InputStream stream,
            String contentType,
            String filename) {
        public Download {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(stream, "stream");
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(filename, "filename");
        }
    }

    public record BatchResult(int claimed, int completed, int retrying, int failed) {
        public BatchResult {
            if (claimed < 0 || completed < 0 || retrying < 0 || failed < 0
                    || completed + retrying + failed != claimed) {
                throw new IllegalArgumentException("invalid audit export batch counts");
            }
        }
    }

    private record ExportRecord(
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
        static ExportRecord from(AuditRecord record) {
            return new ExportRecord(
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
