package io.wyrmgate.iam.audit.siem;

import io.wyrmgate.iam.audit.application.AuditRecordRepository;
import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.ClaimedTenantWork;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Claims technical work and publishes immutable Audit evidence outside authoritative transactions. */
public final class AuditSiemDeliveryService {

    public static final String HANDLER_TYPE = "audit.siem";
    public static final String SUBJECT_TYPE = "audit-record";

    private final JdbcScheduledWorkRepository scheduledWork;
    private final AuditRecordRepository records;
    private final AuditSiemPublisher publisher;
    private final Duration claimLease;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Clock clock;
    private final String leaseOwner;

    public AuditSiemDeliveryService(
            JdbcScheduledWorkRepository scheduledWork,
            AuditRecordRepository records,
            AuditSiemPublisher publisher,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay) {
        this(
                scheduledWork,
                records,
                publisher,
                claimLease,
                batchSize,
                maxAttempts,
                retryDelay,
                Clock.systemUTC(),
                "audit-siem-" + UUID.randomUUID());
    }

    AuditSiemDeliveryService(
            JdbcScheduledWorkRepository scheduledWork,
            AuditRecordRepository records,
            AuditSiemPublisher publisher,
            Duration claimLease,
            int batchSize,
            int maxAttempts,
            Duration retryDelay,
            Clock clock,
            String leaseOwner) {
        this.scheduledWork = Objects.requireNonNull(scheduledWork, "scheduledWork");
        this.records = Objects.requireNonNull(records, "records");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.claimLease = positive(claimLease, "claimLease");
        this.retryDelay = positive(retryDelay, "retryDelay");
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("batchSize out of range");
        if (maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("maxAttempts out of range");
        if (leaseOwner == null || leaseOwner.isBlank()) throw new IllegalArgumentException("leaseOwner must not be blank");
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseOwner = leaseOwner;
    }

    public BatchResult deliverAvailable() {
        List<ClaimedTenantWork> claimed = scheduledWork.claimDueByHandler(
                HANDLER_TYPE, leaseOwner, clock.instant(), claimLease, batchSize);
        int completed = 0;
        int retrying = 0;
        int terminal = 0;
        for (ClaimedTenantWork item : claimed) {
            switch (deliverOne(item)) {
                case COMPLETED -> completed++;
                case RETRYING -> retrying++;
                case TERMINAL -> terminal++;
            }
        }
        return new BatchResult(claimed.size(), completed, retrying, terminal);
    }

    private Outcome deliverOne(ClaimedTenantWork item) {
        UUID recordId;
        try {
            recordId = UUID.fromString(item.work().workKey());
        } catch (IllegalArgumentException invalid) {
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, "audit_siem_invalid_work", clock.instant());
            return Outcome.TERMINAL;
        }
        AuditRecord record = records.findById(item.tenant(), recordId).orElse(null);
        if (record == null) {
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, "audit_siem_record_missing", clock.instant());
            return Outcome.TERMINAL;
        }
        if (item.work().subject() != null
                && (!SUBJECT_TYPE.equals(item.work().subject().subjectType())
                || !record.id().equals(item.work().subject().subjectId()))) {
            scheduledWork.markCompleted(
                    item.tenant(), item.work().id(), leaseOwner, "audit_siem_invalid_work", clock.instant());
            return Outcome.TERMINAL;
        }

        AuditSiemMessage message = new AuditSiemMessage(
                AuditSiemMessage.VERSION,
                record.id(),
                item.tenant().tenantId(),
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
        try {
            publisher.publish(message);
        } catch (AuditSiemDeliveryException failure) {
            return fail(item, failure.retryable(), failure.errorCode());
        } catch (RuntimeException failure) {
            return fail(item, true, "audit_siem_delivery_failed");
        }

        scheduledWork.markCompleted(item.tenant(), item.work().id(), leaseOwner, clock.instant());
        return Outcome.COMPLETED;
    }

    private Outcome fail(ClaimedTenantWork item, boolean retryable, String code) {
        Instant now = clock.instant();
        if (retryable && item.work().attemptCount() < maxAttempts) {
            scheduledWork.reschedule(
                    item.tenant(),
                    item.work().id(),
                    leaseOwner,
                    now.plus(retryDelay),
                    code,
                    now);
            return Outcome.RETRYING;
        }
        scheduledWork.markCompleted(item.tenant(), item.work().id(), leaseOwner, code, now);
        return Outcome.TERMINAL;
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private enum Outcome { COMPLETED, RETRYING, TERMINAL }

    public record BatchResult(int claimed, int completed, int retrying, int terminal) {
        public BatchResult {
            if (claimed < 0 || completed < 0 || retrying < 0 || terminal < 0
                    || completed + retrying + terminal != claimed) {
                throw new IllegalArgumentException("invalid Audit SIEM batch counts");
            }
        }
    }
}
