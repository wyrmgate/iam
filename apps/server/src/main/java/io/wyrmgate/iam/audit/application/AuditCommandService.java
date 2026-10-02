package io.wyrmgate.iam.audit.application;

import io.wyrmgate.iam.audit.domain.AuditRecord;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository;
import io.wyrmgate.iam.platform.persistence.JdbcScheduledWorkRepository.SubjectReference;
import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class AuditCommandService implements SecurityAuditPort {
    private final AuditRecordRepository repository;
    private final TransactionExecutor transactions;
    private static final String SIEM_HANDLER_TYPE = "audit.siem";
    private static final String SIEM_SUBJECT_TYPE = "audit-record";

    private final Clock clock;
    private final JdbcScheduledWorkRepository scheduledWork;
    private final boolean siemEnabled;

    public AuditCommandService(
            AuditRecordRepository repository,
            TransactionExecutor transactions,
            Clock clock) {
        this(repository, transactions, clock, null, false);
    }

    public AuditCommandService(
            AuditRecordRepository repository,
            TransactionExecutor transactions,
            Clock clock,
            JdbcScheduledWorkRepository scheduledWork,
            boolean siemEnabled) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduledWork = scheduledWork;
        this.siemEnabled = siemEnabled;
        if (siemEnabled && scheduledWork == null) {
            throw new IllegalArgumentException("scheduledWork is required when SIEM delivery is enabled");
        }
    }

    @Override
    public AuditRecord append(TenantContext tenant, AuditRecordDraft draft) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(draft, "draft");
        return transactions.required(() -> appendInside(tenant, draft));
    }

    private AuditRecord appendInside(TenantContext tenant, AuditRecordDraft draft) {
        var existing = repository.findById(tenant, draft.id());
        if (existing.isPresent()) {
            AuditRecord replay = requireSame(tenant, existing.get(), draft);
            scheduleSiem(tenant, replay);
            return replay;
        }
        Instant recordedAt = Instant.now(clock);
        AuditRecord proposed = new AuditRecord(
                draft.id(),
                draft.occurredAt(),
                recordedAt,
                draft.actorId(),
                draft.actionType(),
                draft.resourceType(),
                draft.resourceId(),
                draft.outcome(),
                draft.correlationId(),
                draft.causationId(),
                draft.materialSnapshot(),
                AuditRecordIntegrity.derive(tenant, draft, recordedAt));
        if (repository.insertIfAbsent(tenant, proposed)) {
            scheduleSiem(tenant, proposed);
            return proposed;
        }
        AuditRecord raced = repository.findById(tenant, draft.id())
                .orElseThrow(() -> new AuditRecordConflictException(draft.id()));
        AuditRecord replay = requireSame(tenant, raced, draft);
        scheduleSiem(tenant, replay);
        return replay;
    }

    private void scheduleSiem(TenantContext tenant, AuditRecord record) {
        if (!siemEnabled) return;
        Instant now = clock.instant();
        scheduledWork.enqueue(
                tenant,
                SIEM_HANDLER_TYPE,
                record.id().toString(),
                new SubjectReference(SIEM_SUBJECT_TYPE, record.id(), 1),
                now,
                now);
    }

    private static AuditRecord requireSame(
            TenantContext tenant,
            AuditRecord existing,
            AuditRecordDraft draft) {
        if (!AuditRecordIntegrity.verifies(tenant, existing)) {
            throw new AuditIntegrityException(existing.id());
        }
        if (!existing.semanticallyEquals(draft)) {
            throw new AuditRecordConflictException(draft.id());
        }
        return existing;
    }
}
